# Agent-based Private Action Runner setup

This project can expose its seven demo actions as buttons on the Datadog
dashboard. The buttons run Workflow Automation workflows, and an Agent-based
Private Action Runner (PAR) calls five Kubernetes `ClusterIP` services. The
control endpoints are never exposed through an Ingress or public load balancer.

PAR is optional. If you only want the application, DSM, and dashboard, keep
`enable_workflows = false` and use:

```bash
scripts/demo-control <kind|eks> <baseline|stop|opening-rush|sell-off|buying-power-delay|push-outage|redrive>
```

## 1. Prepare the Datadog Application Key

The Application Key mounted into the Datadog Agent must have:

- the `on_prem_runner_write` scope
- **Actions API Access** enabled

Those settings cover runner enrollment; they do not by themselves authorize
Terraform to manage dashboards, monitors, metric configuration, APM retention,
and workflows. Give the Application Key used by each process only the Datadog
permissions needed for that process, and use the Terraform plan as the
pre-apply permission check. If you use separate keys, inject each one as
`DD_APP_KEY` only for the corresponding deploy or Terraform command.

Remote Configuration must also be enabled for the Datadog organization. Store
the key in a password manager or secret manager and inject it only into the
deployment process. Do not put the resolved key in Terraform variables, Helm
values, or this repository.

This repository uses the Agent-based enrollment flow. Do not create a
standalone runner token or manually supply a runner URN; those belong to the
standalone installation method.

## 2. Deploy the Operator, Agent, and applications

Run the normal kind or EKS deployment. The scripts install the pinned Datadog
Operator, and the Helm chart creates the `DatadogAgent` resource.

The chart follows Datadog's Kubernetes Operator pattern:

- the node Agent and Cluster Agent annotations enable PAR in both supported
  execution locations
- both configurations allow only `com.datadoghq.http.request` and the five
  demo service hostnames in the `brokerage-demo` namespace
- the Cluster Agent `-E=/etc/datadog-agent/privateactionrunner.yaml` argument
  loads the generated PAR configuration file

Both annotations are intentional. A Private HTTP Connection selects one
enrolled runner; that selected runner performs the request. The annotations do
not cause every runner to execute each workflow step.

## 3. Verify automatic enrollment

Wait for the Agent rollout, then inspect the Cluster Agent log:

```bash
kubectl -n datadog get pods
kubectl -n datadog logs \
  -l app.kubernetes.io/component=cluster-agent \
  --tail=1000 | grep -i private
```

Look for PAR identity creation, successful self-enrollment, and runner startup.
Then open **Actions > Private Action Runners** in Datadog and confirm that the
runner for the intended cluster is healthy. Do not continue if the displayed
cluster is not the one this dashboard should control.

## 4. Create five Private HTTP Connections

Open **Workflow Automation > Connections**, select **New Connection > HTTP**,
and create one Connection for each row. Set **Type** to **Private Action
Runner**, select the runner verified above, and use **Token authentication**.
Set the credential file path to
`/etc/datadog-agent/brokerage-credentials/http-token.json`. The deployment projects
only the operator credential into the runner Secret and mounts it read-only;
keep this matched to the selected cluster. Do not put token values in Terraform
or workflow parameters.

| Suggested name | Base URL | Terraform variable | Used by |
|---|---|---|---|
| `brokerage-demo-simulator` | `http://order-flow-simulator.brokerage-demo.svc.cluster.local:8080` | `simulator_connection_id` | ベースラインに戻す, 注文フロー停止, 寄付き集中, 材料による売り殺到 |
| `brokerage-demo-exchange` | `http://exchange-gateway-simulator.brokerage-demo.svc.cluster.local:8080` | `exchange_connection_id` | ベースラインに戻す |
| `brokerage-demo-buying-power` | `http://buying-power-service.brokerage-demo.svc.cluster.local:8080` | `buying_power_connection_id` | ベースラインに戻す, 買付余力の反映遅延 |
| `brokerage-demo-push-gateway` | `http://push-gateway-simulator.brokerage-demo.svc.cluster.local:8080` | `push_gateway_connection_id` | ベースラインに戻す, プッシュ通知事業者の障害 |
| `brokerage-demo-exception` | `http://exception-management-service.brokerage-demo.svc.cluster.local:8080` | `exception_connection_id` | 未達通知の再送 (Redrive) |

These are the same five hostnames as the Helm chart's PAR `http_allowlist`.
A base URL for any other host is rejected by the runner.

Authentication is required even for this synthetic ClusterIP-only demo.
An unauthenticated connection now receives HTTP 401. The runner's hostname
allowlist, workload NetworkPolicies, and bearer credential are separate
boundaries. See [security and migration](security-and-migration.md).
The HTTP private-action credential format is documented at
https://docs.datadoghq.com/actions/connections/private_action_credentials/.

Record each Connection UUID from its Datadog connection details. A Connection
is bound to one selected runner, so do not reuse EKS connection IDs for a kind
dashboard or vice versa.

## 5. Enable the Terraform workflows

In the target's ignored `.tfvars` file, set:

```hcl
enable_workflows           = true
simulator_connection_id    = "REPLACE_WITH_SIMULATOR_CONNECTION_UUID"
exchange_connection_id     = "REPLACE_WITH_EXCHANGE_CONNECTION_UUID"
buying_power_connection_id = "REPLACE_WITH_BUYING_POWER_CONNECTION_UUID"
push_gateway_connection_id = "REPLACE_WITH_PUSH_GATEWAY_CONNECTION_UUID"
exception_connection_id    = "REPLACE_WITH_EXCEPTION_CONNECTION_UUID"
```

Run `scripts/datadog-terraform plan` and inspect the plan before applying it.
The five IDs are optional only while `enable_workflows` is false. Terraform
rejects missing, zero, or malformed IDs when workflows are enabled.

Apply creates seven workflows, each with a dashboard button on the
**デモ操作** (Operator Controls) tab:

| Workflow | Button | Endpoint |
|---|---|---|
| 証券デモ - ベースラインに戻す | **ベースラインに戻す** | Four steps: simulator `/internal/demo/baseline`, exchange `/internal/demo/reset`, buying-power `/internal/demo/recover`, push gateway `/internal/demo/recover` |
| 証券デモ - 注文フロー停止 | **注文フロー停止** | simulator `/internal/demo/stop` |
| 証券デモ - 寄付き集中 | **寄付き集中 · 60秒** | simulator `/internal/demo/opening-rush` |
| 証券デモ - 材料による売り殺到 · D105 | **材料による売り殺到 · D105** | simulator `/internal/demo/sell-off` |
| 証券デモ - 買付余力の反映遅延 | **買付余力の反映遅延** | buying-power `/internal/demo/delay` |
| 証券デモ - プッシュ通知事業者の障害 | **プッシュ通知事業者の障害** | push gateway `/internal/demo/outage` |
| 証券デモ - 未達通知の再送 (Redrive) | **未達通知の再送 (Redrive)** | exception management `/internal/demo/redrive` |

After apply, run **ベースラインに戻す** (Reset to Baseline) from **デモ操作** (Operator Controls) and verify its
Workflow run history before trying the other scenarios.

## Official documentation

https://docs.datadoghq.com/actions/private_actions/

https://docs.datadoghq.com/actions/private_actions/use_private_actions/

https://docs.datadoghq.com/actions/connections/http/
