# Local pipeline integration test

`tests/run_local_pipeline.py` is an opt-in test of all ten packaged applications
against disposable local PostgreSQL and Kafka resources. It checks application
startup, Kafka listener readiness, HTTP authentication and JSON, order-key
idempotency, BUY/SELL execution, buying-power/holdings/trade-report/notification
reflection, push outage retries, persisted dead letters and bounded redrive.

It writes orders and changes provider/simulator controls in the test resources.
Use a newly prepared test database and a dedicated test broker. The script never
creates, migrates, resets or deletes database/broker resources. It starts and stops
only its own Java child processes. Never reuse a presentation's broker, offsets,
database or credentials for this test.

## Prerequisites

- Java 21 or later, Python 3.10 or later, and `lsof` on the host.
- Package all application JARs with `cd apps && ./mvnw -B verify`.
- A disposable PostgreSQL server on a loopback, explicit non-default port.
  Create one uniquely named `*_test` database, the seven schema-scoped runtime
  roles below, and separate migration roles. Run the application's migration-only
  entry points with the migration roles before testing, including initial demo
  account/position seeding. Runtime roles must have the grants produced by those
  migrations; the harness disables automatic Flyway execution.
- A dedicated Kafka test broker on loopback port 19092. The pinned Strimzi Kafka
  image can run `.github/kafka-test-server.sh`; this unauthenticated test-only
  broker must remain isolated and must not serve a deployed environment. Create
  every topic listed in `Topics.ALL` with replication factor 1 before starting
  the applications. CI demonstrates the broker's private Podman pod setup.
- HTTP ports 18100 through 18109 must be free. The harness reserves all ports
  before starting Java and verifies each listener belongs to its own child PID
  before sending HTTP requests.

## Private configuration

Run the command from the repository root. A configured `root` value, if present,
must match this checkout; the application paths are derived from the test script.

Set `BROKERAGE_E2E_CONFIG` to a JSON file outside tracked content, owned by your
user and readable only by your user (`chmod 600`). The configuration has this
shape; replace the password and generate four independent random tokens in a
private file. Never paste real deployment credentials into it.

```json
{
  "database": "pipeline_unique_test",
  "java": "/absolute/path/to/java/bin/java",
  "kafka_bootstrap": "127.0.0.1:19092",
  "tokens": {
    "operator": "<64 lowercase hex characters>",
    "simulator": "<different 64 lowercase hex characters>",
    "order": "<different 64 lowercase hex characters>",
    "notification": "<different 64 lowercase hex characters>"
  },
  "services": {
    "order-service": {
      "DATABASE_URL": "jdbc:postgresql://127.0.0.1:55440/pipeline_unique_test",
      "DATABASE_USERNAME": "brokerage_order_service",
      "DATABASE_PASSWORD": "<isolated runtime password>",
      "SPRING_FLYWAY_ENABLED": "false"
    }
  }
}
```

The `services` object must contain all seven entries, each with exactly the four
environment keys illustrated above. Every URL must point to the same loopback
server and selected `*_test` database. URL credentials, URL options, port 5432,
remote hosts, extra environment overrides and migration/superuser role names are
rejected before starting any application.

| Service | Runtime role |
| --- | --- |
| `order-service` | `brokerage_order_service` |
| `buying-power-service` | `brokerage_buying_power_service` |
| `exchange-gateway-simulator` | `brokerage_exchange_gateway` |
| `holdings-service` | `brokerage_holdings_service` |
| `trade-report-service` | `brokerage_trade_report_service` |
| `order-state-service` | `brokerage_order_state_service` |
| `exception-management-service` | `brokerage_exception_management_service` |

Generate each ephemeral token with `secrets.token_hex(32)` in Python, writing it
directly to the private configuration. The harness derives incoming SHA-256
verifiers itself and gives an outgoing raw token only to the three services
that call other services. Parent Datadog/cloud credentials, JVM agent options,
proxy settings and Spring overrides are not inherited. Tracing, DSM and metric
exports are disabled for this local functional test.

```bash
BROKERAGE_E2E_CONFIG=/private/tmp/pipeline-test-config.json \
  python3 tests/run_local_pipeline.py
```

A passing run ends with `LOCAL END-TO-END PASS`. The printed private temporary
log directory contains one log per service and has mode 0700. The script leaves
the database and broker running for inspection; their owner must remove those
disposable resources afterwards. It does not test Datadog instrumentation,
Kubernetes network policies or cloud deployment behavior.

The default Python test suite runs only the guard tests, without starting
services or contacting a database/broker:

```bash
python3 -m unittest discover -s tests -p test_local_pipeline_guards.py -v
```
