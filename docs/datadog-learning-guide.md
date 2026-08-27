# Reading the demo in Datadog

This guide is for Kafka developers who are new to Datadog. It explains which
part of the demo emits each telemetry type, what the main DSM and APM signals
mean, and which deliberate shortcuts affect what you see.

## The four instrumentation paths

| Mechanism | What it does here | Where to inspect it |
|---|---|---|
| Datadog Java tracer | Automatically instruments HTTP and Kafka, creates APM spans, creates DSM checkpoints, and propagates trace and pathway context | APM and Data Streams Monitoring |
| OpenTelemetry API | `EventLogContext` adds `order_id`, `event_type`, and the optional scenario ID to the active Datadog span | APM span attributes |
| Micrometer with StatsD registry | Emits bounded `demo.fx.*` business metrics and Spring Boot's bound Kafka client metrics through DogStatsD | Dashboard and Metrics Explorer |
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
active Datadog span; it does not create a second tracing pipeline.

`observation-enabled` is independent of DSM. Removing it does not disable DSM,
and enabling it does not create DSM checkpoints. It remains enabled so the
Spring Kafka factories expose their standard Observation hooks and timing.

## Read DSM first, then use APM for execution detail

Use DSM to answer topology and flow questions:

- Which producer, topic, and consumer form this pathway?
- Is throughput changing?
- Is a consumer group accumulating offset or time lag?
- At which edge or end-to-end pathway is time accumulating?

Use APM to answer request and code-path questions:

- What work occurred inside one produce or consume operation?
- Which child operation failed or waited?
- Are the consume and subsequent produce spans causally connected?
- Which logs belong to the same trace and `order_id`?

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

When a dashboard graph and an APM graph differ, first check their metric names,
`pathway_type`, `start`, `end`, service, topic, consumer group, and time window.

## Default and full topology views

All services except `order-state-service` emit DSM data by default.

- `dead-letter-service` stays enabled so the DLT consumer and redrive producer
  complete the retry/DLT/redrive learning path.
- `order-state-service` stays disabled because it consumes every lifecycle
  topic and makes the primary map substantially busier.
- As a consequence, `fx.orders.rejected.v1` has a producer edge but no consumer
  edge in the default DSM map. Its only consumer is `order-state-service`; this
  is expected, not an instrumentation failure.

To compare the complete projection topology, redeploy with:

```bash
export FX_DEMO_DSM_FULL_TOPOLOGY=true
scripts/kind-deploy               # or: scripts/deploy eks
```

Return the variable to `false` and redeploy to restore the primary-flow view.

## Retry headers: preserving business metadata without reusing trace context

The execution adapter implements three failure-routing hops:

```text
fx.orders.approved.v1
  -> fx.execution.retry.5s.v1
  -> fx.execution.retry.30s.v1
  -> fx.execution.dlt.v1
```

An earlier implementation copied every incoming Kafka header into the outgoing
retry record. That also copied `dd-pathway-ctx`, `x-datadog-*`, `traceparent`,
and `tracestate`. The Java tracer replaces propagation header values before
sending, so duplicate headers were not the problem. The subtler issue was that
producer instrumentation could extract the old context already present on the
outgoing record and choose it as the producer span's parent instead of the
active consume span.

The corrected implementation copies only five application-owned headers:

```text
fx-retry-count
fx-failure-category
fx-failure-timestamp
fx-originating-topic
fx-redriven
```

The tracer then creates fresh trace and DSM propagation headers from the active
consume-to-produce operation. This restores the expected APM parent-child
relationship without manual span creation. The redrive producer was not
affected: it already constructed a new empty `RecordHeaders` instance.

This distinction matters because a DSM map can still look plausible while an
APM trace has the wrong causal parent. When implementing custom retry topics,
treat framework-owned propagation headers as framework-owned; copy only the
business metadata your application defines.

## Deliberate traffic and failure behavior

### Baseline, surge, and stop

Baseline submits 2 orders/s. Surge changes the same request-rate control to 20
orders/s for 90 seconds and then automatically returns to 2 orders/s. Running
`baseline` before the 90 seconds expire also returns to 2 orders/s. `stop`
explicitly sets the rate to zero while already accepted orders continue.

### Expected rejections

The quantity distribution selects 7.5 million base-currency units for 5% of
generated orders. The fictional pre-trade maximum is USD 5 million, so those
orders are deliberately rejected and produce steady traffic on
`fx.orders.rejected.v1`. This is a demo control, not a real credit, compliance,
or market-risk rule.

The ten orders marked by the processing-failure scenario use a quantity of
100,000 base-currency units. That keeps them below the fictional maximum so
they reach `execution-adapter` and demonstrate both retry topics and the DLT.
The adapter routes only scenario IDs beginning with `processing-failure-`;
other scenario labels remain tracking metadata and do not imply failure.

### Retry timing and lag

The 5-second and 30-second retry listeners deliberately use blocking
`Thread.sleep` calls. The execution consumer factory has concurrency 3, so the
sleep occupies consumer threads. An injected execution delay is applied after
the retry-topic wait as well as on the approved-order path. The resulting lag
is an intentional part of the visual demo, not a production retry scheduler.

In a production system, prefer a retry mechanism that does not occupy consumer
threads and test its ordering, rebalance, backpressure, and failure behavior.

## Data semantics that are intentionally synthetic

- `quantity` is expressed in the base currency: USD for `USD/JPY`, EUR for
  `EUR/USD`, and GBP for `GBP/USD`.
- `Money.usdEquivalent` uses fixed demonstration conversion rates. USD needs no
  conversion, so the `USD/JPY` multiplier is `1`; this is not an FX market rate
  for the pair.
- The traffic seed makes the sequence of generated side, pair, and quantity
  values reproducible by emission count. Wall-clock timing is not deterministic:
  scheduler timing and surge/stop actions change when emissions occur.
- Execution prices, fees, exposure, and settlement outcomes are fictional.

## Partitions, concurrency, and lag

EKS uses six partitions per topic. kind uses three to reduce local resources.
`execution-adapter` uses concurrency 3, so kind can consume all three partitions
in parallel. On EKS, six partitions provide more partition-level parallelism
than the single execution-adapter replica can use at once. Partition-level lag
may therefore look different between the two environments even at the same
traffic rate.

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
2. Identify the received, approved, executed, confirmed, and settlement path.
3. Trigger delay and compare DSM edge/full latency, consumer lag, pending-order
   gauges, and APM consumer duration.
4. Reset baseline and confirm lag and pending state drain.
5. Trigger the ten-order failure and follow the retry and DLT topics.
6. Open one affected `order_id` in logs and APM; verify consume-to-produce span
   parentage across retry hops.
7. Redrive and confirm the DLT gauge returns to zero and downstream execution
   completes.

## Official documentation

https://docs.datadoghq.com/data_streams/

https://docs.datadoghq.com/data_streams/metrics_and_tags/

https://docs.datadoghq.com/data_streams/setup/technologies/kafka/

https://docs.datadoghq.com/tracing/trace_collection/dd_libraries/java/
