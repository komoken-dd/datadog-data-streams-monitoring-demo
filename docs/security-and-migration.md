# Security defaults and migration

The demo uses fictional data. Its security boundary is still explicit: an
unrelated workload must not submit orders, change reservations, inject failures,
redrive notifications, or read another service's database tables.

## Credentials and HTTP access

Deployment scripts provision random credentials through stdin. Resolved values
are not passed to Helm, Terraform, curl arguments, or kubectl arguments.
Existing Secrets are preserved; rotating one requires a coordinated rollout.

| Identity | Allowed use |
|---|---|
| Operator | Demo controls, diagnostics, order submission and inquiry |
| Simulator | Submit/inquire its logical orders, read quotes, start the two exchange scenarios |
| Simulator read access | Read fictional buying-power accounts and order projections |
| Order service | Reserve, inquire, and cancel a not-yet-accepted reservation |
| Notification service | Deliver a push notification |

Applications receive SHA-256 verifiers for incoming credentials. Only clients
receive the plaintext credential they must send. The operator credential is not
injected into application containers. A verifier is not a usable bearer token.
Health/readiness/liveness GET requests remain public for Kubernetes probes;
metrics, other Actuator endpoints, and business/control endpoints require the
appropriate bearer credential. Missing/invalid verifier configuration prevents
startup. This is workload authentication for synthetic accounts, not a real
customer authentication system.

The generated `brokerage-demo-api-auth` Secret in the application namespace
contains independent `<identity>-token` and `<identity>-token-sha256` entries for
`operator`, `simulator`, `order`, and `notification`. The scripts project only the
operator credential into `brokerage-demo-runner-http` in the `datadog` namespace.
The runner mounts its `http-token.json` at:

```text
/etc/datadog-agent/brokerage-credentials/http-token.json
```

Private HTTP Connections must use **Token authentication** with that credential
file. Do not enter bearer values in workflow parameters or Terraform variables.
See [Private Action Runner](private-action-runner.md). The CLI reads the selected
operator Secret and passes the header to curl over stdin. Alternatively inject
`BROKERAGE_DEMO_OPERATOR_TOKEN` into that CLI child process from a secret manager.
Do not print it or put it in shell history.

`BROKERAGE_DEMO_API_SECRET_NAME` and `BROKERAGE_DEMO_RUNNER_SECRET_NAME` select
alternative Secret names. Keep application and runner credentials matched.
When `BROKERAGE_DEMO_CREATE_K8S_SECRETS=false` on EKS, provision all API, runner,
admin, runtime, and migration Secrets externally first; deployment does not
silently generate replacements.

## Database privileges and initialization

The PostgreSQL administrator Secret is used only by PostgreSQL initialization
and the database bootstrap Job.
Each database-backed application has a distinct runtime login and migration
login in `brokerage-demo-db-<service>`, with `runtime-password` and
`migration-password` keys. There are seven database-backed applications;
`order-service` now persists submissions and accepted-order outbox records.

Bootstrap creates service schemas and assigns their ownership to the matching
migration role. It grants runtime only schema usage and business-table DML plus
required sequence access. Runtime roles have no superuser, role-management,
replication, database-creation, schema-creation, cross-service schema access, or
migration-history privileges. The epoch-budget table is read-only to runtime;
its narrowly scoped trigger performs the counter update under its migration
owner with a fixed search path.

Migration logins are trusted DDL identities, separate from runtime. Immutable
legacy V1 migrations contain `CREATE SCHEMA IF NOT EXISTS`, which PostgreSQL
requires database `CREATE` permission to execute even for an existing schema.
Only migration identities receive that database privilege; their `CREATEDB`
role attribute remains false. Their credentials are mounted only into the
bootstrap Job and matching migration init container.

A migration init container runs the same packaged application with
`BROKERAGE_DEMO_MIGRATE_ONLY=true`. It runs Flyway and exits **before** creating
application beans, listeners, schedulers, or HTTP servers. Normal containers use
runtime credentials with Flyway disabled. New business tables inherit runtime
privileges; Flyway history and the epoch budget are explicitly restricted before
the normal container starts. The original administrator login is not demoted or
renamed in place because existing volumes depend on it.

For an existing volume, bootstrap moves a legacy per-service history table from
`public` into its owned schema and preserves its checksums. If both old and new
history tables exist, bootstrap fails closed for operator review. It never
chooses one silently, repairs checksums, deletes tables, or resets balances.

## Network policy enforcement

The chart creates namespace default-deny policies plus required DNS, Kafka,
PostgreSQL, HTTP, runner, and node-local telemetry flows. Strimzi's own listener
`networkPolicyPeers` restricts client ingress; an additional restrictive policy
alone cannot override a broad Strimzi-generated allow rule.

Fresh kind clusters use Cilium instead of kindnet. Existing kindnet clusters are
not converted in place: `scripts/kind-up` stops with migration guidance. Preserve
any needed data and use a reviewed separate environment or an explicit whole-epoch
reset. The scripts never delete a cluster automatically.

`scripts/kind-up` enables Cilium `policyCIDRMatchMode=nodes` and agent rollout on
configuration changes. Cilium otherwise excludes node identities from CIDR
selectors: broker-to-Kubernetes API traffic (needed by Strimzi's Secret config
provider) and node-local telemetry would be denied despite the private-address
`ipBlock` rules. API egress is scoped to Kafka pods on TCP 443/6443; application
pods receive only the separate telemetry ports. The rules use standard Kubernetes
NetworkPolicy types for compatibility with the EKS VPC CNI; verify enforcement
on each target cluster. See the [Cilium CIDR policy documentation](https://docs.cilium.io/en/stable/security/policy/layer3/#cidr-based).

EKS CDK configures a compatible managed VPC CNI add-on with network-policy
support. Standard enforcement mode allows a brief startup convergence window;
fail-closed HTTP authentication is an independent boundary during that window.
Do not interpret presence of a NetworkPolicy object or a ready DaemonSet alone
as proof of enforcement. After deployment run:

```bash
scripts/verify-network-provider
scripts/verify-network-isolation kind  # or: eks
```

The isolation check creates temporary probes. It first proves PostgreSQL and
Kafka are reachable by an allowed source, then verifies an unrelated namespace
cannot reach either. It verifies DNS independently and removes only its probes.
It does not send an order or change a balance. A dedicated kind test context can
be supplied as the second argument.

HTTP/Kafka/PostgreSQL transport remains inside the cluster and is not mTLS.
Network isolation and scoped HTTP authentication reduce the trusted workload
set; they do not protect against a compromised node or packet capture by a
privileged cluster operator. Do not expose these Services publicly.

## Upgrade an existing demo

These changes require a coordinated release, not a rolling mixture of old and
new applications. Review the image lock, CDK diff, Helm manifests, and database
migration evidence before changing a running environment.

1. Stop generation and prevent other operators from submitting scenarios/orders.
2. Drain accepted orders, executions, retry topics, DLT/redrive work, and outboxes;
   reconcile unknown submissions and held reservations. Retain diagnostics.
3. Back up any synthetic state/history needed for investigation. The previously
   observed stale reservations are not automatically released by these changes.
4. Choose an epoch strategy. New tombstones cannot recover completed exchange IDs
   already purged by older versions. Before permitting full historical replay,
   use an explicitly approved whole-epoch replacement of Kafka **and** PostgreSQL,
   or prohibit replay before a documented cutover boundary. Do not clear only
   the database, only offsets, or only deduplication rows.
5. Review/apply CDK networking and log reconciliation when using EKS. Upgrade
   Strimzi CRDs from the exact chart digest before the operator; Helm does not
   upgrade existing CRDs automatically. Conflicts stop the deployment instead
   of forcing field ownership. See the [runbook](demo-runbook.md).
6. Provision the new credential Secrets, render the Helm chart, and run the
   database bootstrap/migration init containers. Verify runtime roles and history
   privileges on actual tables before resuming generation.
7. Upgrade all Avro readers before producing framed messages. The decoder accepts
   original raw v1 records and the new writer-schema-aware format; old binaries
   cannot read the new format. Include queued retry/outbox payloads in planning.
8. Update the five Private HTTP Connections to use the mounted token credential.
9. Verify readiness of all ten services, authenticated controls, negative access
   tests, network isolation, and `scripts/smoke-test`. Check actual JVM versions,
   deployed digests, Datadog/DSM telemetry, and scenario behavior, then resume.

If a step fails, keep generation stopped and preserve the current snapshot.
After framed messages or new database migrations exist, blindly rolling back to
an old binary is unsafe. Recovery must retain compatible readers and the correct
Kafka/database epoch.

## Bounded operation and evidence

A durable 50,000-logical-submission limit bounds one demo epoch. It survives
service restarts and concurrent requests; repeating an idempotency key does not
consume another slot. At the limit, intake returns
`429 DEMO_EPOCH_LIMIT_REACHED` before reserving funds. Rejections and epoch usage
are observable. Increasing/resetting the epoch is an explicit operator action,
not an automated deletion of replay identities.

Use the read-only `scripts/demo-epoch-plan` and the runbook's offline epoch
procedure. Keep unresolved work and compact dedupe/tombstone records until a
whole-epoch replacement. Automatic age-based deletion of financial identities
is intentionally absent.

The validation workflow exercises PostgreSQL failure windows, actual Kafka
committed offsets, Avro old/new bytes, transaction rollback, and dependency/image
checks. See [dependency security](dependency-security.md) and
[switch-trade preparation](switch-trade-preparation.md). Passing a dependency
scanner is not a security certification; vendor-unfixed findings remain visible.
