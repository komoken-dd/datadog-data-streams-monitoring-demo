#!/usr/bin/env bash

# Shared application-image source validation for kind and EKS deployments.

readonly FX_DEMO_RELEASE_REPOSITORY="komoken-dd/datadog-data-streams-monitoring-demo"
readonly FX_DEMO_RELEASE_WORKFLOW="${FX_DEMO_RELEASE_REPOSITORY}/.github/workflows/release-images.yml"
readonly FX_DEMO_RELEASE_IMAGE_PREFIX="ghcr.io/${FX_DEMO_RELEASE_REPOSITORY}"
readonly FX_DEMO_SERVICES=(
  order-generator order-service pre-trade-control-service execution-adapter position-service
  risk-service confirmation-service settlement-service order-state-service dead-letter-service
)

require_image_source() {
  local image_source="$1"
  case "${image_source}" in
    release|self-built) ;;
    *) die "FX_DEMO_IMAGE_SOURCE must be 'release' or 'self-built'." ;;
  esac
}

service_names_json() {
  jq -cn '$ARGS.positional' --args "${FX_DEMO_SERVICES[@]}"
}

download_and_verify_release_image_values() {
  local release="$1"
  local output_file="$2"
  local release_asset
  local release_url
  local source_revision
  local service
  local reference
  local expected_prefix
  local digest
  local services_json

  require_command curl
  require_command gh
  require_command jq
  if [[ ! "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
    die "FX_DEMO_RELEASE must be a semantic release tag such as v1.0.0."
  fi

  release_asset="fx-demo-images-${release}.json"
  release_url="https://github.com/${FX_DEMO_RELEASE_REPOSITORY}/releases/download/${release}/${release_asset}"
  echo "Application image source: verified GHCR release ${release}"
  echo "Release image lock: ${release_url}"
  curl --fail --location --silent --show-error --output "${output_file}" "${release_url}"

  source_revision="$(jq -er '.release.sourceRevision' "${output_file}")"
  [[ "${source_revision}" =~ ^[0-9a-f]{40}$ ]] || die "The release image lock contains an invalid source revision."
  services_json="$(service_names_json)"
  jq -e \
    --arg release "${release}" \
    --arg revision "${source_revision}" \
    --argjson services "${services_json}" \
    '(keys | sort) == ["image", "release", "version"]
     and (.release | keys | sort) == ["schemaVersion", "sourceRevision", "tag"]
     and (.image | keys) == ["references"]
     and .release.schemaVersion == 1
     and .release.tag == $release
     and .release.sourceRevision == $revision
     and .version == $release
     and (.image.references | type == "object")
     and ((.image.references | keys | sort) == ($services | sort))' \
    "${output_file}" >/dev/null || die "The release image lock has an invalid schema or service set."

  for service in "${FX_DEMO_SERVICES[@]}"; do
    reference="$(jq -er --arg service "${service}" '.image.references[$service]' "${output_file}")"
    expected_prefix="${FX_DEMO_RELEASE_IMAGE_PREFIX}/${service}:${release}@sha256:"
    digest="${reference#"${expected_prefix}"}"
    if [[ "${reference}" != "${expected_prefix}"* || ! "${digest}" =~ ^[0-9a-f]{64}$ ]]; then
      die "Unexpected release image reference for ${service}: ${reference}"
    fi
  done

  gh attestation verify "${output_file}" \
    --repo "${FX_DEMO_RELEASE_REPOSITORY}" \
    --signer-workflow "${FX_DEMO_RELEASE_WORKFLOW}" \
    --source-ref "refs/tags/${release}" \
    --source-digest "${source_revision}" \
    --deny-self-hosted-runners >/dev/null
  echo "Release image lock: verified GitHub Artifact Attestation"
}

validate_self_built_image_values() {
  local values_file="$1"
  local services_json
  local version
  local service
  local reference
  local expected_fragment
  local digest

  require_command jq
  [[ -f "${values_file}" ]] || die "Self-built image values file not found: ${values_file}"
  services_json="$(service_names_json)"
  jq -e \
    --argjson services "${services_json}" \
    '(keys | sort) == ["image", "version"]
     and (.image | keys) == ["references"]
     and (.version | type == "string" and length > 0)
     and (.image.references | type == "object")
     and ((.image.references | keys | sort) == ($services | sort))' \
    "${values_file}" >/dev/null || die "The self-built image values file has an invalid schema or service set."

  version="$(jq -er '.version' "${values_file}")"
  [[ "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$ ]] || die "Invalid self-built image version: ${version}"
  for service in "${FX_DEMO_SERVICES[@]}"; do
    reference="$(jq -er --arg service "${service}" '.image.references[$service]' "${values_file}")"
    expected_fragment="/${service}:${version}@sha256:"
    if [[ "${reference}" == *"://"* || "${reference}" == /* || "${reference}" == *[[:space:]]* || "${reference}" != *"${expected_fragment}"* ]]; then
      die "Unexpected self-built image reference for ${service}: ${reference}"
    fi
    digest="${reference##*@sha256:}"
    [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "Invalid digest for self-built image ${service}: ${reference}"
  done

  echo "Application image source: self-built references from ${values_file}"
  echo "Self-built images are not covered by this repository's GitHub Artifact Attestations."
}
