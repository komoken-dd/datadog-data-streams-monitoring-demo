# Demo Presentation Steps

This guide provides a repeatable presentation. Use
[Demo runbook](demo-runbook.md) for deployment and recovery commands.

## Before presenting

Confirm:

- Baseline traffic is reaching Datadog.
- All application Deployments and Kafka nodes are ready.
- DSM shows the intended services and Kafka topics.
- The dashboard time picker includes the last 15 minutes.
- No screen contains credentials, organization administration pages, customer
  information, or unrelated telemetry.

## Core story

### 1. Introduce the application

Suggested explanation:

> This is a Kafka order-processing demo instrumented with Datadog. The FX
> orders and all monetary values are generated and fictional. The purpose is
> to connect Kafka behavior with application and business impact, not to model
> a real trading system.

Show the main path from order generation to confirmation and settlement, then
point out the position, risk, and confirmation fan-out.

### 2. Establish baseline

Run **Reset to Baseline**.

In **Demo Overview**, confirm:

- Orders received and executions completed rise at similar rates.
- Pending orders and consumer lag stay low.
- Unresolved dead letters is zero.
- Order-to-execution percentiles are stable.

Open Data Streams Monitoring and identify producers, topics, consumers, and
the executed-trade fan-out.

### 3. Inject delay

Run **Inject Execution Delay** and return to **Demo Overview**.

Explain the evidence in this order:

1. Executions stop keeping pace with received orders.
2. Pending count and fictional pending notional rise.
3. Order-to-execution p95 and p99 rise.
4. DSM shows lag on the approved-order execution pathway.
5. APM points to increased processing time in `execution-adapter`.

The important lesson is that DSM identifies the affected stream path while
business metrics show why the delay matters.

### 4. Investigate one order

Choose one `order_id` from the dashboard transaction list.

- Filter logs by the order ID.
- Confirm the order, event, currency-pair, and scenario attributes.
- Follow the related spans across HTTP and Kafka.
- Explain that the order ID is searchable in logs and spans but is not a
  metric tag.

### 5. Restore the baseline

Run **Reset to Baseline**.

Show consumer lag and pending gauges returning toward zero. Executions may
temporarily rise while the queued work drains. Duration percentiles can remain
high until delayed samples leave the selected time window.

## Extended retry and dead-letter story

### 6. Inject deterministic failures

Run **Fail Next 10 Orders**. The returned scenario ID remains useful for
investigating the affected orders, but it is not required for redrive.

Show:

- Retry attempts appearing before dead-letter events
- The 5-second and 30-second retry topics in DSM
- Exactly ten unresolved dead letters
- The selected orders remaining non-terminal

### 7. Redrive all unresolved orders

Run **Redrive All Dead Letters** and show:

- Unresolved dead letters returning to zero
- The redriven orders reaching execution and downstream processing
- The original `order_id` remaining searchable
- A new event ID and redrive metadata distinguishing the new attempt

## Closing points

- DSM explains Kafka pathways, throughput, latency, and lag.
- APM explains time spent inside services.
- Logs and spans support high-cardinality order investigation.
- Bounded custom metrics communicate current business impact.
- The sample demonstrates observability patterns, not production trading or
  guaranteed atomic event publication.
