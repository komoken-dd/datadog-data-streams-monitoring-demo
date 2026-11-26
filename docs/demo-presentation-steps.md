# Demo Scenarios and Presentation Steps

This guide describes the five scenarios and a repeatable presentation built
from them. Use the [demo runbook](demo-runbook.md) for deployment and recovery
commands.

Each scenario can be started from the dashboard's **デモ操作** (Operator Controls) tab
when Workflow Automation is enabled, or with the CLI:

```bash
scripts/demo-control <kind|eks> <baseline|stop|opening-rush|sell-off|buying-power-delay|push-outage|redrive>
```

| Dashboard button | CLI action | Calls |
|---|---|---|
| **ベースラインに戻す** | `baseline` | `order-flow-simulator` `/internal/demo/baseline`, `exchange-gateway-simulator` `/internal/demo/reset`, `buying-power-service` `/internal/demo/recover`, `push-gateway-simulator` `/internal/demo/recover` |
| **注文フロー停止** | `stop` | `order-flow-simulator` `/internal/demo/stop` |
| **寄付き集中 · 60秒** | `opening-rush` | `order-flow-simulator` `/internal/demo/opening-rush` |
| **材料による売り殺到 · D105** | `sell-off` | `order-flow-simulator` `/internal/demo/sell-off` |
| **買付余力の反映遅延** | `buying-power-delay` | `buying-power-service` `/internal/demo/delay` |
| **プッシュ通知事業者の障害** | `push-outage` | `push-gateway-simulator` `/internal/demo/outage` |
| **未達通知の再送 (Redrive)** | `redrive` | `exception-management-service` `/internal/demo/redrive` |

The dashboard's **デモ概要** (Demo Overview) tab has five groups referenced below:
**1 · Executive Summary**, **2 · Business Flow & Market**,
**3 · Orders Requiring Attention**, **4 · Streaming & Application Diagnosis**,
and **5 · Kafka & Kubernetes Runtime**.

The numbers under "What you should see" were observed on a local kind
deployment with the default settings (baseline 3 orders/s, three partitions).
Treat them as approximate; they vary with timing, hardware, and how long each
scenario runs.

## Scenario reference

### 1. Opening rush (寄付き集中)

**Trigger:** **寄付き集中 · 60秒** or `opening-rush`.

The simulator puts the exchange into a 60-second pre-open session and submits
25 orders/s. Orders are accepted and reserved but do not fill. When the
pre-open ends, the opening auction (板寄せ) matches them at one price and the
exchange publishes a single burst of executions. The simulator then returns
to baseline on its own.

**Business effect:** this is the 9:00 open. Every order placed before the open
executes at once, and buying power, holdings, trade reports, notifications,
and order inquiry must all absorb the burst.

**What you should see:**

- **Working orders · now** rises to about 1,300 during the pre-open while
  **Executions (約定)** stays flat.
- At the auction, all of them fill in one burst.
  **Unreflected buying-power executions · now** jumps to about 260 and drains
  in about 12 seconds.
- **Order to first execution · p50 / p95 / p99** shows the pre-open wait.
- In DSM, consumer lag on `brokerage.executions.v1` rises briefly for the
  fan-out consumer groups and drains without intervention. This is the
  healthy case to contrast with scenario 2.

### 2. Buying-power reflection delay (「売ったのに買えない」)

**Trigger:** **買付余力の反映遅延** or `buying-power-delay`.

`buying-power-service` adds 2,500 ms of processing to every execution and
expiry it consumes. Other consumers of the same topic are unaffected.

**Business effect:** executions are confirmed at the exchange, but sale
proceeds are not yet reflected in buying power. A customer who sells and
immediately buys with the proceeds (a switch trade) is rejected with
`INSUFFICIENT_BUYING_POWER`: "I sold, but I can't buy."

**What you should see:**

- In DSM, `buying-power-service-v1` lag on `brokerage.executions.v1` grows by
  about 3.5 messages/s, to about 420 after two minutes, while the other
  consumer groups on the topic stay near zero. With 2-minute max rollups,
  Datadog showed up to about 170 in the verification run; the exact value
  depends on the rollup and time window.
- **Unreflected buying-power executions · now** grows to about 420, and
  **Oldest unreflected execution · now** reaches about 100 seconds.
- **Buying-power reflection delay · p95** rises above the 5-second threshold
  of the reflection-delay monitor.
- **Rejects by reason and switch-trade results**: switch-trade BUY rejections
  rise from about 1 per minute to about 8 per minute. In
  **3 · Orders Requiring Attention**, the rejected-orders list shows
  `INSUFFICIENT_BUYING_POWER` for the switch-trader accounts
  (`A00451` to `A00500`).
- In APM, `buying-power-service` consumer spans are slow, while the
  synchronous reservation call inside `POST /orders` stays fast. The
  bottleneck is the asynchronous reflection, not the order check.

**Recovery:** **ベースラインに戻す** drained the lag in under 10 seconds in the
verification run.

### 3. Push provider outage and redrive

**Trigger:** **プッシュ通知事業者の障害** or `push-outage`.

The push gateway answers every request with HTTP 503 after about 800 ms.
`notification-service` routes each failed notification to the 5-second retry
topic, then the 30-second retry topic, then the DLT, and
`exception-management-service` stores it.

**Business effect:** trades are booked correctly, but customers are not told
that their orders filled or expired.

**What you should see:**

- **Notifications · delivered, retried, and dead-lettered** shows retries
  first; after about 40 seconds notifications reach the DLT.
- **Push gateway · requests by result** switches to `unavailable`.
- Undelivered notifications grow by about 4 per second.
- **Redrive 待ちの通知 DLT · 現在** keeps growing while the
  outage lasts (265 in the verification run) and the
  **Unresolved notification dead letters** monitor alerts.
- In DSM, the two retry topics and the DLT gain throughput, and the
  `exception-management-service` DLT consumer appears on the map.
- **通知 DLT の発生履歴 · 解決済みを含む** in
  **3 · Orders Requiring Attention** lists historical affected orders. Delivered
  notifications remain in this history; it is not a current unresolved list.

**Recovery and redrive:** run **ベースラインに戻す** first so the push provider
is healthy, then **未達通知の再送 (Redrive)**. The CLI prints the
result, for example `{"redrivenCount":100,"remainingCount":165,...}`. Each call
queues a bounded batch (default: up to 100 records with a five-second Kafka
send budget and a 15-second database transaction timeout). Repeat
while `remainingCount` is greater than zero; concurrent calls select separate
batches. If a batch fails or times out, database changes for that batch roll
back, and a subsequent call can retry it. Already acknowledged Kafka writes
can still be delivered, so notification identity remains stable. The dead-letter count decreases
when records are queued for retry, before delivery is confirmed. Confirm the
delivery counter increases and the execution-notification pending gauge drains;
that gauge does not include expiry notifications. Follow the order's delivery
logs for both execution and expiry notifications. `GET /internal/exceptions`
provides the current Redrive-waiting records through authenticated operator
access. In the earlier verification run, delivery drained within about 12
seconds. The simulated provider deduplicates `notification_id` within its
in-memory bounded window; it does not guarantee lifetime exactly-once delivery
across provider restarts. Redriving while the provider is still down sends
notifications through the retry topics to the DLT again.

### 4. Recovery

**Trigger:** **ベースラインに戻す** or `baseline`.

Reset restores baseline order flow, resets the exchange to a continuous
session at base prices (cancelling any sell-off), removes the buying-power
delay, and recovers the push provider.

**What you should see:** consumer lag, unreflected executions, and working
orders drain toward their baseline values. Executions and buying-power updates
can temporarily rise while queued work drains. Percentile widgets can stay
high until delayed samples leave the selected time window. Unresolved dead
letters stay until you redrive them.

### 5. News-driven sell-off (材料による売り殺到) and limit-down (ストップ安)

**Trigger:** **材料による売り殺到 · D105** or `sell-off`.

For 120 seconds the simulator adds about 25 sell orders/s for `D105` (Demo
Semiconductor), mostly at market, on top of baseline traffic. The exchange
pushes the price down until it reaches the daily lower limit.

**Business effect:** holders rush to sell on bad news. Once the price hits
limit-down, sell orders stop filling and pile up, and at the end the unfilled
remainder expires. Customers see unfilled sell orders and then expiry
notifications.

**What you should see:**

- **Market price · symbol** and **Market snapshot**: `D105` falls from 12,500
  to about 11,260 after 15 seconds, to 10,000 after 30 seconds, and to the
  limit-down price of 9,500 after about 40 to 45 seconds. The TSE limit width
  for a base price between 10,000 and 15,000 yen is 3,000 yen. The limit-down
  flag turns on.
- While limit-down, sell orders do not fill. Between 105 and 120 seconds,
  **Working orders · now** reaches about 2,600 and
  **Unfilled SELL quantity · now** reaches about 1.1 to 1.5 million `D105`
  shares.
- At the end of the 120-second window, the unfilled sell orders expire with
  reason `LIMIT_DOWN_UNFILLED`, the expired-orders list in
  **3 · Orders Requiring Attention** fills up, and buying-power reservations
  are released. `brokerage.orders.expired.v1` shows a burst in DSM, consumed
  by `buying-power-service` and `notification-service`.
- Records are keyed by `account_id`, so the `D105` burst is spread across
  partitions instead of landing on a single symbol partition.

`D105` stays at limit-down after the window until **ベースラインに戻す** resets
the exchange.

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

> This is the order and buying-power flow of a fictional online brokerage,
> instrumented with Datadog. The instruments, accounts, and prices are
> fictional. Buying power is checked synchronously before an order is
> accepted; Kafka fans out execution reports from the exchange to buying power,
> holdings, trade reports, notifications, and order inquiry.

Show the synchronous order path and the execution fan-out in DSM. Point out
that the fan-out starts at the exchange, because the exchange is an external
system whose execution reports arrive independently of the order request.

### 2. Establish baseline

Run **ベースラインに戻す**.

In **1 · Executive Summary**, confirm:

- Orders accepted and executions rise steadily.
- Working orders and unreflected executions stay low.
- Unresolved notification dead letters is zero.
- Reflection and delivery delay p95 are low and stable.

Explain the few baseline rejections: market BUYs reserve at the daily upper
limit, and an account with a pending sell cannot sell the same shares again.

### 3. Opening rush

Run **寄付き集中 · 60秒** and narrate the pre-open accumulation,
the auction burst, and the short lag that drains on its own.

### 4. Buying-power reflection delay

Run **買付余力の反映遅延**. Explain the evidence in this order:

1. Switch-trade BUY rejections for insufficient buying power rise.
2. Unreflected executions and the oldest unreflected age grow.
3. DSM shows lag on `brokerage.executions.v1` for `buying-power-service-v1`
   only.
4. APM shows slow `buying-power-service` consumer spans while order intake
   stays fast.

The lesson: DSM identifies which consumer of a fan-out topic is behind, and
business metrics show why it matters to customers.

### 5. Investigate one order

Choose one rejected switch-trade BUY or one order from the lifecycle list in
**3 · Orders Requiring Attention**.

- Set the dashboard's `account_id` or `order_id` template variable, or filter
  logs by `@order_id`.
- Find the switch trade's SELL execution and when its buying power was
  reflected, relative to the rejected BUY.
- Follow the related spans across HTTP and Kafka, and explain why the
  execution trace starts at the exchange.
- Explain that the account and order IDs are searchable in logs and spans but
  are not metric tags.

### 6. Restore the baseline

Run **ベースラインに戻す** and show lag and unreflected executions draining.

## Extended stories

### 7. Push outage, DLT, and redrive

Run **プッシュ通知事業者の障害**. Show retries, then dead letters, the DSM retry
and DLT pathways, and the monitor. Run **ベースラインに戻す**, then
**未達通知の再送 (Redrive)**, and show unresolved dead letters
returning to zero with no push duplicates.

Emphasize that only notifications have retry topics and a DLT. Orders are
never re-sent automatically.

### 8. Sell-off to limit-down

Run **材料による売り殺到 · D105**. Show the price
falling to limit-down, the unfilled sell quantity piling up, and the expiry
burst at the end. Finish with **ベースラインに戻す**.

## Closing points

- DSM explains Kafka pathways, throughput, latency, and lag per consumer group.
- APM explains time spent inside services, including the synchronous
  buying-power check.
- Logs and spans support high-cardinality account and order investigation.
- Bounded custom metrics communicate current customer impact.
- The sample demonstrates observability patterns, not production trading or
  guaranteed atomic event publication.
