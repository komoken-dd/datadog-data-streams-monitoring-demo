# Datadog Data Streams Monitoring Demo

[日本語](README.ja.md)

Datadog Data Streams Monitoring (DSM) maps producers, topics, and consumers in
event-driven applications and measures pathway latency, throughput, and
consumer lag. It helps identify where messages slow down or accumulate and
which service is involved.

This project demonstrates how to instrument a Kafka-based Java application
with DSM, APM, logs, and custom metrics. It simulates the order and
buying-power flow of a retail online brokerage trading
Japanese cash equities: customers place orders for five fictional
instruments (`D101` to `D105`), buying power is checked synchronously, and
Kafka fans out execution reports to buying power, holdings, trade reports,
customer notifications, and order inquiry. It is intended for developers who
know Kafka but are new to Datadog observability, including engineers who
build trading systems.

> [!IMPORTANT]
> This is an independent learning project, not an official Datadog sample or a
> production reference architecture. It uses no real market, customer,
> account, or trading data. All instruments, accounts, orders, prices, and
> executions are generated and fictional.

## What you can learn

- How the Datadog Java tracer observes Kafka producer and consumer pathways
- How DSM relates pathway latency, throughput, and consumer lag per consumer
  group on a fan-out topic
- How unified service tags correlate DSM, APM, logs, and Kubernetes workloads
- How to search orders and accounts without high-cardinality metrics
- How retry topics, a dead-letter topic, and redrive appear in DSM
- How business metrics explain the customer impact of a streaming backlog

Use the [Datadog learning guide](docs/datadog-learning-guide.md) to connect each
instrumentation mechanism to what appears in Datadog.

## What the demo includes

The application contains ten Spring Boot services, nine Kafka topics managed
by Strimzi, and PostgreSQL. The included Datadog Terraform creates a
dashboard, three monitors, metric metadata and tag configurations, an APM
retention filter, and, when enabled, seven Workflow Automation controls.

The design follows real online-brokerage practice where it matters:

- Buying power or sellable quantity is checked and reserved synchronously
  before an order is accepted, so rejections reach the customer immediately.
- Kafka carries the execution fan-out, where lag reaches customers: sale
  proceeds not yet reflected, stale order inquiry, late notifications.
- Retry, dead-letter, and redrive apply only to customer notifications, which
  are idempotent. Orders are never re-sent automatically.
- Every record is keyed by `account_id`, preserving per-account ordering for
  buying power without a hot partition during a sell-off.

After deployment, you can:

- Explore the order and execution topology and Kafka health in DSM
- Correlate customer impact with pathway latency, lag, traces, and logs
- Trigger the opening rush, a buying-power reflection delay, a push provider
  outage with redrive, recovery, and a news-driven sell-off down to limit-down
- Follow one `order_id` or `account_id` through its events, spans, retries,
  and final outcome

Dashboard and DSM screenshots from the previous version of this demo were
removed and will be recaptured for the online-brokerage version.

The dashboard's operator controls run the scenarios through Workflow
Automation and an Agent-based Private Action Runner; the control endpoints
remain ClusterIP-only. The context-validating `scripts/demo-control` CLI
provides the same controls without Workflow Automation. See the
[Private Action Runner guide](docs/private-action-runner.md) to enable the
dashboard buttons.

## Architecture

See the [detailed architecture diagram](docs/architecture.md#system-and-observability-flow)
for all services, Kafka topics, persistence, instrumentation, and telemetry
paths, and for the market rules and deliberate simplifications.

The stack uses Java 21, Spring Boot, Spring for Apache Kafka, Apache Avro,
Strimzi Kafka in KRaft mode, PostgreSQL, Helm, AWS CDK, and the Datadog
Terraform Provider. Podman is the tested local container runtime; GitHub
Actions builds release images.

## Getting started

Choose either Kubernetes target. Both run the same application and provide the
same Datadog learning experience.

| Target | Environment | Kafka and storage |
|---|---|---|
| kind | Local Kubernetes | One Kafka node and development storage |
| Amazon EKS | Kubernetes in your AWS account | One Kafka node and persistent EBS storage |

Both paths require Bash on macOS or Linux, kubectl, Helm, curl, jq, the GitHub
CLI, and a Datadog API/Application Key pair. Windows users should use WSL2.
The local path additionally requires kind, OpenSSL, and Podman 6 or later,
with at least 12 GiB of Podman machine memory (16 GiB verified). JDK 21 is
needed only for host-side Java tests. Untested Docker substitutions are
described in the [runbook](docs/demo-runbook.md#docker-compatibility-note-not-tested).

Load `DD_API_KEY`, `DD_APP_KEY`, and `DD_SITE` at runtime from a password manager
or secret manager. Do not store resolved keys in the repository or shell
history.

The deployment scripts support verified multi-architecture GHCR release
images, but no release containing the online-brokerage images has been
published yet; the existing `v0.1.0` release predates this domain. Until a new
release is available, build the images yourself as shown below. See the
[container image policy](docs/container-images.md) for provenance and the
release path.

### Local Kubernetes with kind

```bash
scripts/kind-up
TARGET=kind scripts/build-images
BROKERAGE_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy
scripts/smoke-test kind
```

After deployment, filter DSM to `env:local`. The smoke test submits a market
BUY of 100 shares of `D101` and waits until it is filled with buying power,
trade report, and notification reflected for every execution.

### Amazon EKS

> [!WARNING]
> EKS creates billable AWS resources and sends telemetry to Datadog. One
> on-demand worker, the control plane, one NAT gateway, and EBS volumes remain
> billable until teardown. This single-AZ data plane is cost-conscious but is
> not a high-availability production topology.

The EKS path adds an authenticated AWS CLI, Node.js, npm, an explicit
administrator CIDR and IAM role, PostgreSQL credentials, and a registry the
worker nodes can pull from. Follow the [demo runbook](docs/demo-runbook.md) for
infrastructure creation, image builds, deployment, Datadog Terraform,
validation, and teardown.

## What to observe in Datadog

| View | What this demo shows |
|---|---|
| Data Streams Monitoring | Service/topic topology, pathway latency, throughput, and consumer lag per consumer group |
| APM and logs | The synchronous buying-power check, searchable account and order context, and drill-down from an affected pathway |
| Dashboard and metrics | Working orders, unreflected executions, reflection and notification delays, rejections, market prices, and dead letters |
| Workflow Automation | Repeatable scenario controls and execution history |

The Datadog Admission Controller injects a pinned Java tracer. It automatically
instruments supported Kafka clients and propagates trace and DSM pathway
context; application code does not create DSM checkpoints manually.

DSM is enabled for every application except `order-state-service` by default,
because that projection consumes all nine topics and can obscure the primary
path. Set `BROKERAGE_DEMO_DSM_FULL_TOPOLOGY=true` before either deployment to
include it. Structured logs carry account, order, and event context, while
attributes on active spans support order-level investigation without using
unique IDs as metric tags.

See the [Datadog learning guide](docs/datadog-learning-guide.md) for the full
instrumentation map, DSM/APM differences, retry-header propagation, and metric
conventions.

## Demo scenarios

Use the dashboard's operator controls or the CLI fallback:

```bash
scripts/demo-control <kind|eks> <baseline|stop|opening-rush|sell-off|buying-power-delay|push-outage|redrive>
```

| Scenario | Action | What happens |
|---|---|---|
| Opening rush | `opening-rush` | 60 seconds of pre-open orders, then one opening-auction burst of executions |
| Buying-power reflection delay | `buying-power-delay` | Sale proceeds are reflected late, so switch-trade BUYs are rejected for insufficient buying power |
| Push provider outage and redrive | `push-outage`, then `baseline` and `redrive` | Notifications move through both retry topics into the DLT and are redriven after recovery |
| Recovery | `baseline` | Lag drains and buying power catches up |
| News-driven sell-off | `sell-off` | `D105` falls to limit-down; unfilled sell orders pile up and expire |

See [demo-presentation-steps.md](docs/demo-presentation-steps.md) for what to
run, where to look in DSM and on the dashboard, and the approximate values to
expect.

## Documentation

| Document | Purpose |
|---|---|
| [Architecture](docs/architecture.md) | System, Kafka, persistence, telemetry, market rules, and simplifications |
| [Learning guide](docs/datadog-learning-guide.md) | How to read DSM and relate it to APM, logs, and metrics |
| [Demo runbook](docs/demo-runbook.md) | Deployment, operation, validation, and teardown |
| [Scenarios and presentation](docs/demo-presentation-steps.md) | The five scenarios and a repeatable presentation |
| [Container image policy](docs/container-images.md) | Image sources, provenance, and digest policy |
| [Private Action Runner](docs/private-action-runner.md) | Dashboard workflow-control setup |

Source code is under `apps/`, Kubernetes configuration under `deploy/`, AWS and
Datadog infrastructure under `infra/`, and operator commands under `scripts/`.

Official Datadog references:

- https://docs.datadoghq.com/data_streams/
- https://docs.datadoghq.com/data_streams/setup/technologies/kafka/
- https://docs.datadoghq.com/tracing/trace_collection/dd_libraries/java/
- https://docs.datadoghq.com/actions/private_actions/

Schema Tracking background is available at
https://docs.datadoghq.com/data_streams/schema_tracking/. This demo does not
deploy a Schema Registry and remains functional when Schema Tracking is
unavailable.

## Security and upgrades

Use the [security and migration guide](docs/security-and-migration.md) before
upgrading an existing environment. Deployment now requires Python 3, enforces
API credentials and network isolation, and separates runtime database roles
from migration identities. A coordinated Kafka/database epoch is required for
replay safety; the scripts do not reset existing data automatically.

## Support and license

Questions and bug reports are accepted through GitHub Issues; pull requests
are not accepted. Do not include credentials, customer information, production
payloads, or unsanitized logs. See [CONTRIBUTING.md](CONTRIBUTING.md) and report
security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

Licensed under the [MIT License](LICENSE).
