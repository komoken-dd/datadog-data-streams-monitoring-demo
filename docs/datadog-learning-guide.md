# Reading the demo in Datadog

This guide is for Kafka developers who are new to Datadog. It explains which
part of the demo emits each telemetry type, what the main DSM and APM signals
mean, and which deliberate shortcuts affect what you see.

## The four instrumentation paths

| Mechanism | What it does here | Where to inspect it |
|---|---|---|
| Datadog Java tracer | Automatically instruments HTTP and Kafka, creates APM spans, creates DSM checkpoints, and propagates trace and pathway context | APM and Data Streams Monitoring |
| OpenTelemetry API | `EventLogContext` adds business identifiers to the active span; durable outbox relays create `outbox.publish` spans carrying those identifiers around the actual Kafka send | APM spans and attributes |
| Micrometer with StatsD registry | Emits bounded `demo.brokerage.*` business metrics and Spring Boot's bound Kafka client metrics through DogStatsD | Dashboard and Metrics Explorer |
| Spring Observation | Enables Spring Kafka's framework observation hooks and timing | Framework observations; not DSM enablement |

The injected Java tracer is the component that produces DSM data. Application
code does not call a manual DSM checkpoint API. The relevant workload settings
are:

```text
DD_DATA_STREAMS_ENABLED=true
DD_TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED=true
DD_LOGS_INJECTION=true
DD_TRACE_OTEL_ENABLED=true
```

`DD_TRACE_OTEL_ENABLED=true` is required because `EventLogContext` uses
`Span.current()` from the OpenTelemetry API. It changes attributes on the
active Datadog span. Outbox publication also creates a parent span through that
same bridge; it does not create a separate telemetry-export pipeline.

`observation-enabled` is independent of DSM. Removing it does not disable DSM,
and enabling it does not create DSM checkpoints. It remains enabled so the
Spring Kafka factories expose their standard Observation hooks and timing.

## Instrumentation by service

| Service | HTTP | Kafka consume | Kafka produce | PostgreSQL schema | DSM by default |
|---|---|---|---|---|---|
| `order-flow-simulator` | Calls `order-service` and the exchange quote API | | | | Enabled (no Kafka clients) |
| `order-service` | Serves idempotent `POST /orders` and submission inquiry; calls `buying-power-service` | | `orders.accepted` (durable outbox relay) | `order_service` | Enabled |
| `buying-power-service` | Serves the reservation API | `executions`, `orders.expired` | `buying-power.updated` | `buying_power_service` | Enabled |
| `exchange-gateway-simulator` | Serves `GET /internal/market/quotes` | `orders.accepted` | `executions`, `orders.expired` (from the matching scheduler) | `exchange_gateway` | Enabled |
| `holdings-service` | | `executions` | | `holdings_service` | Enabled |
| `trade-report-service` | | `executions` | `trade-reports.issued` | `trade_report_service` | Enabled |
| `notification-service` | Calls the push gateway | `executions`, `orders.expired`, `notifications.retry.5s`, `notifications.retry.30s` | `notifications.delivered`, both retry topics, `notifications.dlt` | | Enabled |
| `push-gateway-simulator` | Serves `POST /push` | | | | Enabled (no Kafka clients) |
| `order-state-service` | Serves `GET /internal/orders/{orderId}` | All nine topics | | `order_state_service` | Disabled |
| `exception-management-service` | Serves `GET /internal/exceptions` | `notifications.dlt` | `notifications.retry.5s` (redrive) | `exception_management_service` | Enabled |

Topic names in this table omit the `brokerage.` prefix and `.v1` suffix.

All ten services receive the same unified service tags, log injection, and
Micrometer export. Every service that accepts demo controls also serves
`/internal/demo/*` endpoints on its ClusterIP service.

## Read DSM first, then use APM for execution detail

Use DSM to answer topology and flow questions:

- Which producer, topic, and consumer form this pathway?
- Is throughput changing?
- Is a consumer group accumulating offset or time lag?
- At which edge or end-to-end pathway is time accumulating?

Use APM to answer request and code-path questions:

- How long did the synchronous buying-power reservation take inside
  `POST /orders`?
- What work occurred inside one produce or consume operation?
- Which child operation failed or waited?
- Which logs belong to the same trace, `order_id`, and `account_id`?

The `data_streams.latency` metric and the APM-derived
`trace.kafka.consume` metric are therefore not interchangeable:

- `data_streams.latency` describes message movement through a pathway.
- `trace.kafka.consume` describes the duration of an instrumented Kafka
  consumer span in one service.

For `data_streams.latency`, the important tags are:

| Tag | Meaning |
|---|---|
| `pathway_type:full` | End-to-end latency from the data origin to the selected end point |
| `pathway_type:edge` | Time between an upstream produce and downstream consume |
| `pathway_type:partial_edge` | One side of the edge is not known to DSM |
| `pathway_type:internal` | Time from consume to the following produce in one service |
| `start` | Origin for `full`, or upstream node for an edge |
| `end` | Last observed node for `full`, or downstream node for an edge |

For consumer lag, `data_streams.kafka.lag_messages` and
`data_streams.kafka.lag_seconds` are tagged by `topic`, `consumer_group`, and
`partition`. The buying-power monitor, for example, watches
`topic:brokerage.executions.v1` with `consumer_group:buying-power-service-v1`.

When a dashboard graph and an APM graph differ, first check their metric names,
`pathway_type`, `start`, `end`, service, topic, consumer group, and time window.

## Where pathways start: the exchange boundary

The exchange simulator books an accepted order in its Kafka listener and
returns. Matching runs later on a scheduler thread, which publishes executions
and expiries. That thread has no active consume span, so:

- The pathway from `order-service` ends when `exchange-gateway-simulator`
  consumes `brokerage.orders.accepted.v1`.
- The execution fan-out pathways start at `exchange-gateway-simulator` as their
  origin. `pathway_type:full` latency on the fan-out measures time since the
  exchange published the execution, not since the customer placed the order.
- An APM trace for an execution starts at the exchange's outbox relay span and
  produce span, not in the customer's `POST /orders` trace.

This mirrors a real brokerage, where execution reports arrive from an
external exchange independently of the order request. To follow one order
across the boundary, filter logs or spans by `@order_id` or `@account_id`. The
dashboard's `order_id` and `account_id` template variables do the same.
Order-to-first-execution time is measured from the event timestamps and
published as `demo.brokerage.order_to_first_execution.duration`.

## Default and full topology views

All services except `order-state-service` emit DSM data by default.

- `exception-management-service` stays enabled so the DLT consumer and redrive
  producer complete the notification retry/DLT/redrive learning path.
- `order-state-service` stays disabled because it consumes all nine topics and
  makes the primary map substantially busier.
- As a consequence, `brokerage.buying-power.updated.v1`,
  `brokerage.trade-reports.issued.v1`, and
  `brokerage.notifications.delivered.v1` have a producer edge but no consumer
  edge in the default DSM map. Their only consumer is `order-state-service`;
  this is expected, not an instrumentation failure.

To compare the complete projection topology, redeploy with:

```bash
export BROKERAGE_DEMO_DSM_FULL_TOPOLOGY=true
BROKERAGE_DEMO_IMAGE_SOURCE=self-built scripts/kind-deploy   # or: scripts/deploy eks
```

Unset the variable or set it to `false` and redeploy to restore the
primary-flow view.

## Retry headers: preserving business metadata without reusing trace context

`notification-service` implements three failure-routing hops:

```text
brokerage.executions.v1 or brokerage.orders.expired.v1
  -> brokerage.notifications.retry.5s.v1
  -> brokerage.notifications.retry.30s.v1
  -> brokerage.notifications.dlt.v1
```

Copying every incoming Kafka header into the outgoing retry record would also
copy `dd-pathway-ctx`, `x-datadog-*`, `traceparent`, and `tracestate`. The Java
tracer replaces propagation header values before sending, so duplicate headers
are not the problem. The subtler issue is that producer instrumentation can
extract the old context already present on the outgoing record and choose it
as the producer span's parent instead of the active consume span.

`KafkaHeaders.copyDemoMetadata` therefore copies only five application-owned
headers:

```text
brokerage-retry-count
brokerage-failure-category
brokerage-failure-timestamp
brokerage-originating-topic
brokerage-redriven
```

The tracer then creates fresh trace and DSM propagation headers from the active
consume-to-produce operation. This keeps the expected APM parent-child
relationship without manual span creation. The redrive producer in
`exception-management-service` builds a new, empty header set with
`brokerage-redriven=true` and a retry count of 0.

A DSM map can still look plausible while an APM trace has the wrong causal
parent. When implementing custom retry topics, treat framework-owned
propagation headers as framework-owned; copy only the business metadata your
application defines.

## Metric naming and tags

Custom metrics are defined in `MetricNames` and follow
`demo.brokerage.<area>.<measure>`:

| Area | Examples | Emitted by |
|---|---|---|
| Order intake | `orders.accepted`, `orders.rejected` | `order-service` |
| Exchange | `executions.count`, `executions.amount_jpy`, `order_to_first_execution.duration`, `orders.expired`, `market.price`, `market.price_change_pct`, `market.limit_down` | `exchange-gateway-simulator` |
| Buying power | `buying_power.reservations`, `buying_power.updates`, `buying_power.reflection_delay` | `buying-power-service` |
| Notifications | `notifications.delivered`, `notifications.delivery_delay`, `notifications.retry_attempts`, `notifications.dead_letter.events`, `push_gateway.requests` | `notification-service`, `push-gateway-simulator` |
| Exceptions | `notifications.dead_letter.unresolved`, `notifications.dead_letter.oldest_unresolved_age` | `exception-management-service` |
| Current state | `orders.working`, `orders.unfilled_quantity`, `buying_power.unreflected_executions`, `buying_power.oldest_unreflected_age`, `notifications.undelivered` | `order-state-service` |
| Simulator | `scenario.current`, `simulator.switch_trades` | `order-flow-simulator` |

Metric tags are bounded business dimensions only: `symbol`, `side`,
`order_type`, `reason`, `fill_type`, `notification_type`, `failure_category`,
`result`, `redriven`, and `source`. Five symbols, two sides, and a handful of
reasons keep every metric's cardinality small.

Unique identifiers such as `account_id`, `order_id`, `event_id`, and
`notification_id` are never metric tags. They appear in structured log
attributes and span attributes, where Datadog indexes them for search without
creating a time series per order. This is how the dashboard's transaction
lists and the APM retention filter (`@order_id:*`) find individual orders.

### Tag values are lowercase in metrics

Datadog lowercases metric tag values. The application emits `side:SELL`,
`symbol:D105`, and `reason:INSUFFICIENT_BUYING_POWER`, but metric queries must
use `side:sell`, `symbol:d105`, and `reason:insufficient_buying_power`. A filter
with uppercase values silently matches nothing.

Log and span attribute values keep their case. In Log Explorer, search
`@event_type:ORDER_REJECTED` or `@symbol:D105` exactly as emitted.

### Distribution percentiles need tag configuration

Reflection delay, delivery delay, and order-to-first-execution time are
distributions. Their p50, p95, and p99 values are queryable only after
Terraform applies the `datadog_metric_tag_configuration` resources with
`include_percentiles = true`. Before that, percentile widgets and the p95
monitor show no data even though the metric exists.

A tag configuration also limits which tags remain queryable on that metric to
the listed ones, for example `env`, `service`, `side`, `source`, and `version`
for `demo.brokerage.buying_power.reflection_delay`. These configurations apply
to the metric name across the whole Datadog organization.

## Deliberate traffic and failure behavior

### Baseline, stop, and the automatic return to baseline

Baseline submits 3 orders/s across `D101` to `D105`, mixing market and limit
orders and both sides. About 10% of baseline orders start a switch trade: a
cash-poor account sells, and 3 seconds after the sell is accepted the
simulator submits a BUY funded only by those proceeds. `stop` sets the rate to
zero while orders already accepted continue through the exchange and the
fan-out.

The opening-rush and sell-off scenarios return to baseline automatically when
their windows end. Running `baseline` earlier ends them immediately and also
resets the exchange, buying-power reflection, and the push provider.

### Expected rejections at baseline

A few rejections are normal even without a scenario:

- `INSUFFICIENT_BUYING_POWER`: a market BUY reserves at the daily upper limit,
  which can exceed the cash an account still has free.
- `INSUFFICIENT_SELLABLE_QUANTITY`: the account already has a pending sell for
  the same shares.

The buying-power-delay scenario is distinguished by the switch-trade BUY
rejections (`demo.brokerage.simulator.switch_trades` with
`result:rejected_buying_power`) rising well above this baseline.

### Reflection delay and lag

The buying-power-delay scenario adds 2,500 ms of processing to every
execution and expiry in `buying-power-service`. With listener concurrency 3,
the service can reflect only about 1.2 records per second, below the baseline
execution rate, so `buying-power-service-v1` lag on `brokerage.executions.v1`
grows steadily while the other consumer groups on the same topic stay near
zero. That contrast is the point of the scenario: one slow consumer of a
fan-out topic, visible as one lagging consumer group in DSM.

### Retry timing and lag

The 5-second and 30-second retry listeners deliberately block until each
record is due. A record that already waited longer than the delay is
processed immediately, so a backlog drains at delivery speed. The blocking
wait occupies consumer threads, and the resulting lag is an intentional part
of the visual demo, not a production retry scheduler.

During a push outage, each failed push also waits about 800 ms for the
provider's 503 response. The main `notification-service-v1` consumers on
`brokerage.executions.v1` and `brokerage.orders.expired.v1` therefore slow
down as well.

In a production system, prefer a retry mechanism that does not occupy consumer
threads and test its ordering, rebalance, backpressure, and failure behavior.

## Partitions, concurrency, and lag

EKS uses six partitions per topic. kind uses three to reduce local resources.
`buying-power-service` and `notification-service` use listener concurrency 3,
so kind consumes all three partitions in parallel. On EKS, six partitions
provide more partition-level parallelism than one replica with concurrency 3
can use at once. Partition-level lag may therefore look different between the
two environments even at the same traffic rate.

## Where Kafka client metrics come from

Dashboard metrics such as `kafka.producer.record.error.rate` are not collected
by JMXFetch in this project. Spring Boot Actuator binds Kafka producer and
consumer client metrics to Micrometer; the StatsD registry exports them to the
node-local DogStatsD endpoint. They appear only when:

- Datadog export is enabled
- the application has created and used the corresponding Kafka client
- the node Agent and DogStatsD path are healthy
- the dashboard `env` matches the workload's unified service tag

If a widget is empty, verify those conditions before adding a Kafka broker JMX
configuration.

## Suggested investigation sequence

1. Start baseline and select the matching `env` in DSM and the dashboard.
2. Identify the accepted-order pathway into the exchange and the execution
   fan-out from the exchange to buying power, holdings, trade reports,
   notifications, and order inquiry.
3. In APM, open one `POST /orders` trace and find the synchronous call to
   `buying-power-service`.
4. Run the buying-power delay. Compare consumer lag per consumer group on
   `brokerage.executions.v1`, the unreflected-execution gauge, the reflection
   delay p95, and switch-trade rejections.
5. Reset baseline and confirm lag and unreflected executions drain.
6. Run the push outage. Follow notifications through both retry topics into
   the DLT, then open one affected `order_id` in logs and APM and verify the
   consume-to-produce span parentage across retry hops.
7. Reset baseline, redrive, and confirm unresolved dead letters return to zero
   without push duplicates.

## Official documentation

https://docs.datadoghq.com/data_streams/

https://docs.datadoghq.com/data_streams/metrics_and_tags/

https://docs.datadoghq.com/data_streams/setup/technologies/kafka/

https://docs.datadoghq.com/tracing/trace_collection/dd_libraries/java/

https://docs.datadoghq.com/metrics/distributions/

https://docs.datadoghq.com/getting_started/tagging/

### Durable acceptance and matching boundaries

Order acceptance and exchange matching commit their immutable Kafka payloads to
PostgreSQL before a relay sends them. They persist only the append-time W3C
`traceparent` and `tracestate` (bounded to 128/512 characters) and restore that
parent on `outbox.publish`, even after restart. Baggage and arbitrary headers
are excluded. A normal accepted-order relay can therefore continue the HTTP
trace asynchronously. Rows appended without an active span, old rows, and
scheduled reconciliation start fresh traces. The exchange matching scheduler
remains independent of the accepted-order consumer's trace.

Relay spans and logs carry `order_id`, `account_id`, and `event_id` for correlation
where no parent exists. Stored W3C context does not preserve Datadog's separate
DSM pathway context; complete causal DSM paths through a durable outbox have
not been verified. Custom submission pending/outbox age metrics distinguish a
local relay backlog from Kafka consumer lag. Exact stored payloads are sent
using `ByteArraySerializer`; no producer-side automatic Avro schema sampling
is guaranteed for this custom codec.
