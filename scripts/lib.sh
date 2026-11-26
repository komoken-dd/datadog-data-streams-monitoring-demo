#!/usr/bin/env bash

set -Eeuo pipefail

# Used by scripts that source this library.
# shellcheck disable=SC2034
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC2034
readonly PROJECT_ROOT
readonly BROKERAGE_NAMESPACE="${BROKERAGE_NAMESPACE:-brokerage-demo}"
readonly AWS_REGION="${AWS_REGION:-us-west-2}"

require_command() {
  local command_name="$1"
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Required command not found: ${command_name}" >&2
    exit 1
  }
}

die() {
  echo "$*" >&2
  exit 1
}

current_context() {
  kubectl config current-context 2>/dev/null || true
}

require_exact_context() {
  local expected="$1"
  local actual
  actual="$(current_context)"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "Refusing to continue: expected Kubernetes context '${expected}', got '${actual:-<none>}'." >&2
    exit 1
  fi
  echo "Kubernetes context: ${actual}"
  echo "Kubernetes namespace: ${BROKERAGE_NAMESPACE}"
}

require_nonempty() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Required environment variable is unset: ${name}" >&2
    exit 1
  fi
}

require_boolean() {
  local name="$1"
  case "${!name:-}" in
    true|false) ;;
    *) die "${name} must be 'true' or 'false'." ;;
  esac
}

require_datadog_site() {
  local site="$1"
  case "${site}" in
    datadoghq.com|us3.datadoghq.com|us5.datadoghq.com|datadoghq.eu|ap1.datadoghq.com|ap2.datadoghq.com|ddog-gov.com) ;;
    *) die "Unsupported Datadog site: ${site}" ;;
  esac
}

require_datadog_credentials() {
  require_command curl
  require_command jq
  require_nonempty DD_API_KEY
  require_nonempty DD_APP_KEY
  require_nonempty DD_SITE
  require_datadog_site "${DD_SITE}"

  require_command python3
  if ! python3 "${PROJECT_ROOT}/scripts/curl-auth-config.py" datadog | curl -fsS --config - \
    "https://api.${DD_SITE}/api/v2/validate_keys" \
    | jq -e '.status == "ok"' >/dev/null; then
    echo "Refusing to continue: Datadog API/Application Key validation failed for '${DD_SITE}'." >&2
    exit 1
  fi

  echo "Datadog credentials: valid for ${DD_SITE}"
}

apply_datadog_secret() {
  local namespace="$1"
  local secret_name="$2"

  require_command jq
  require_nonempty DD_API_KEY
  require_nonempty DD_APP_KEY

  # Pass keys through stdin, not command arguments or a temporary file.
  jq -n \
    --arg namespace "${namespace}" \
    --arg secret_name "${secret_name}" \
    '{
      apiVersion: "v1",
      kind: "Secret",
      metadata: {namespace: $namespace, name: $secret_name},
      type: "Opaque",
      stringData: {
        "api-key": env.DD_API_KEY,
        "app-key": env.DD_APP_KEY
      }
    }' | kubectl apply --server-side --field-manager=brokerage-demo-deployer -f -
}

restart_datadog_agents() {
  local namespace="$1"

  kubectl -n "${namespace}" wait --for=create daemonset/datadog-agent --timeout=5m
  kubectl -n "${namespace}" wait --for=create deployment/datadog-cluster-agent --timeout=5m
  kubectl -n "${namespace}" rollout restart \
    daemonset/datadog-agent deployment/datadog-cluster-agent
  kubectl -n "${namespace}" rollout status daemonset/datadog-agent --timeout=10m
  kubectl -n "${namespace}" rollout status deployment/datadog-cluster-agent --timeout=10m
}

wait_for_rollout() {
  local deployment="$1"
  kubectl --context "$(current_context)" -n "${BROKERAGE_NAMESPACE}" rollout status "deployment/${deployment}" --timeout=5m
}

start_port_forward() {
  local service="$1"
  local local_port="$2"
  kubectl --context "$(current_context)" -n "${BROKERAGE_NAMESPACE}" port-forward "service/${service}" "${local_port}:8080" >"/tmp/brokerage-demo-${service}-port-forward.log" 2>&1 &
  PORT_FORWARD_PID=$!
  for _ in {1..30}; do
    if curl -fsS "http://127.0.0.1:${local_port}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  kill "${PORT_FORWARD_PID}" 2>/dev/null || true
  echo "Port-forward for ${service} did not become ready." >&2
  return 1
}

ensure_application_secrets() {
  local create_secrets="${1:-true}"
  local secret_name existing service
  require_command python3
  secret_name="${BROKERAGE_DEMO_API_SECRET_NAME:-brokerage-demo-api-auth}"
  existing="$(kubectl -n "${BROKERAGE_NAMESPACE}" get secret "${secret_name}" --ignore-not-found -o name)" || die "Cannot inspect application API Secret."
  if [[ -z "${existing}" ]]; then
    [[ "${create_secrets}" == "true" ]] || die "Provision ${secret_name} externally before deployment."
    python3 "${PROJECT_ROOT}/scripts/secret-manifest.py" api "${BROKERAGE_NAMESPACE}" "${secret_name}" | kubectl create -f - >/dev/null
  fi
  local runner_secret="${BROKERAGE_DEMO_RUNNER_SECRET_NAME:-brokerage-demo-runner-http}"
  if [[ "${create_secrets}" == "true" ]]; then
    kubectl -n "${BROKERAGE_NAMESPACE}" get secret "${secret_name}" -o json \
      | python3 "${PROJECT_ROOT}/scripts/runner-credential-secret.py" datadog "${runner_secret}" \
      | kubectl apply --server-side --field-manager=brokerage-demo-deployer -f - >/dev/null
  else
    kubectl -n datadog get secret "${runner_secret}" >/dev/null
  fi
  for service in order-service buying-power-service exchange-gateway-simulator holdings-service trade-report-service order-state-service exception-management-service; do
    secret_name="brokerage-demo-db-${service}"
    existing="$(kubectl -n "${BROKERAGE_NAMESPACE}" get secret "${secret_name}" --ignore-not-found -o name)" || die "Cannot inspect database runtime Secret."
    if [[ -z "${existing}" ]]; then
      [[ "${create_secrets}" == "true" ]] || die "Provision ${secret_name} externally before deployment."
      python3 "${PROJECT_ROOT}/scripts/secret-manifest.py" database "${BROKERAGE_NAMESPACE}" "${secret_name}" | kubectl create -f - >/dev/null
    fi
  done
}

operator_curl() {
  if [[ -n "${BROKERAGE_DEMO_OPERATOR_TOKEN:-}" ]]; then
    python3 "${PROJECT_ROOT}/scripts/curl-auth-config.py" operator | curl --config - --connect-timeout 3 --max-time 60 "$@"
  else
    kubectl -n "${BROKERAGE_NAMESPACE}" get secret "${BROKERAGE_DEMO_API_SECRET_NAME:-brokerage-demo-api-auth}" -o json \
      | python3 "${PROJECT_ROOT}/scripts/curl-auth-config.py" operator \
      | curl --config - --connect-timeout 3 --max-time 60 "$@"
  fi
}
