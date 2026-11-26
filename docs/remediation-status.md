# Audit remediation status

Validation date: 2026-11-25. Final source review: 2026-11-26.
These changes are implemented in the audit remediation branch.
They have **not** been deployed to an existing demo environment. Existing Issues
remain open pending review and deployment verification.

## Issue coverage

Issue numbers refer to `komoken-dd/datadog-data-streams-monitoring-demo`.

| Issue | Implementation | Evidence |
|---|---|---|
| #19 | Stop a failing Kafka listener without advancing past the failed record | Actual Kafka: repeated failing restarts retain the committed offset; a healthy restart replays the failed and following records |
| #20 | Durable, idempotent order intake, reservation inquiry/cancellation fencing, and accepted-order outbox | PostgreSQL failure-window tests; HTTP idempotency contract; complete order pipeline |
| #21 | Immutable matching outbox, retained completed-order identities and persisted W3C trace parents | PostgreSQL transaction/replay tests, exact persisted wire payloads and parent restoration after relay recreation |
| #22 | Cumulative fill/expiry progress preserves remaining reservations | BUY/SELL reordering and PostgreSQL accounting tests |
| #23 | Bounded redrive with checked-exception rollback and row locking | Transaction and actual PostgreSQL rollback tests; outage/recovery pipeline |
| #24 | Environment-scoped Terraform state, saved-plan validation, locking and consumption | Six isolated local-provider tests, including cross-environment rejection |
| #25 | Inventory/cash preflight, confirmed SELL proceeds and bounded switch-trade coordination | Scenario tests and authenticated ten-service pipeline |
| #26 | Distinguish DLT history, unresolved work, queued redrive and actual delivery | Dashboard, monitor, workflow and runbook definitions reviewed |
| #27 | All ten services expose probes; stopped Kafka listeners fail readiness; smoke test requires holdings receipts | Ten-service health/authentication and BUY/SELL pipeline checks |
| #28 | Writer-schema fingerprint framing with immutable legacy-v1 readers | Actual Avro bytes, evolution, malformed/unknown writer rejection |
| #29 | Required reusable validation before image release | Java, PostgreSQL/Kafka, dependency/image, secret, Helm, CDK and Terraform checks defined in CI |
| #30 | Durable 50,000-submission epoch cap and explicit whole-epoch planning | Concurrent PostgreSQL cap/idempotency tests; read-only planning guards |
| #31 | Reconcile tags and retention on actual managed/indirect CloudWatch log groups | CDK and idempotent reconciliation-handler tests; AWS application pending |

## Security changes

- Patched and SHA-256-pinned Java 21.0.13.1 runtimes for both architectures;
  updated Spring and resolved runtime dependencies; patched Strimzi operator
  and broker images without changing the Kafka protocol version.
- Independent operator/service credentials, incoming credential verifiers,
  restricted service API scopes, authenticated metrics and controls.
- Separate runtime and migration database roles for seven services; no shared
  administrator credential in application containers; migration history and
  epoch-budget privileges restricted.
- Default-deny network policies, restricted Strimzi listener peers, a policy
  enforcing CNI, and executable allowed/denied connectivity checks.
- Secret generation and transport through stdin or child-process environments,
  plus a mounted private-runner HTTP credential file.

See [security defaults and migration](security-and-migration.md) for the trust
boundary and coordinated upgrade procedure. See [dependency security](dependency-security.md)
for image construction, scanning and exception policy.

## Validation and remaining work

- Clean Maven verification: **261 tests, zero failures/errors/skips**, including
  actual PostgreSQL and Kafka integration tests.
- Python regression suite with disposable PostgreSQL: **46 tests passed**.
- All ten packaged applications passed an isolated real Kafka/PostgreSQL
  pipeline using schema-scoped runtime credentials. This covered BUY/SELL,
  idempotent inquiry/replay, holdings receipts, authentication rejection,
  notification failure, retry, DLT, recovery, redrive and redrive no-op.
- CDK build/tests and Terraform validation/mock-provider tests passed locally.
- A disposable Cilium-backed kind cluster passed actual allowed-source
  PostgreSQL/Kafka access and unrelated-namespace denial, with DNS checked
  independently. Node-local TCP 8126 and UDP 8125 telemetry paths also passed.
  The disposable cluster was removed; EKS enforcement remains to be measured.
- Gitleaks found no secrets in publishable source files.
- The 102-component resolved runtime SBOM had no OSV advisory matches at scan
  time. The representative ARM64 runtime had no **fixable** HIGH/CRITICAL OS
  findings. Six vendor-unfixed HIGH findings remain visible in the scan;
  this does not establish exploitability or absence of other vulnerabilities.
- Actual patched application JVM/startup was verified on ARM64. AMD64 runtime
  construction/execution is gated in CI; it was not executed locally. Strimzi
  operator/broker JVMs were checked on both architectures.
- The exact checksum-verified Datadog Java agent 1.69.1 started with Boot 4.1.3
  and served authenticated Spring MVC/Jackson requests without linkage errors.
  Its agent destination was a closed loopback port; this did not validate
  Datadog ingestion, DSM pathways or automatic schema tracking.

The hosted CI workflow has not run for these unpushed changes. Post-deployment
checks must verify deployed digests, database privileges, network isolation,
Private Action Runner authentication, AWS tags/retention and actual Datadog/DSM
telemetry. No Datadog organization or credential selection was inferred.

Final source review covered the financial failure/concurrency paths, deployment
isolation and migration ordering, Terraform plan guards, and CloudWatch
reconciliation. CI PostgreSQL readiness now checks the final TCP listener rather
than the entrypoint's temporary initialization server. The review did not change
existing demo resources or reset historical data.

Older purged exchange identities and existing stale reservations cannot be
reconstructed safely by a code change. Preserve and reconcile historical state;
use a documented replay cutover or an explicitly approved whole-epoch
replacement before historical replay. The upgrade guide details this limit.
