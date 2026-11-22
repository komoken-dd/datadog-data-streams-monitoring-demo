resource "datadog_monitor" "unresolved_notification_dead_letters" {
  name    = "[Brokerage Demo] Unresolved notification dead letters"
  type    = "query alert"
  query   = "avg(last_5m):avg:demo.brokerage.notifications.dead_letter.unresolved{env:${var.environment}} > 0"
  message = <<-EOT
    Execution or expiry notifications (約定通知・失効通知) exhausted the 5s and 30s retry topics and are parked in `brokerage.notifications.dlt.v1`. The trades themselves are booked; affected customers have not been told that their order filled or expired.

    Check:
    - Dashboard → Orders Requiring Attention → Notification dead letters for the affected orders and failure category.
    - Push gateway results (`demo.brokerage.push_gateway.requests` by result) to confirm the push provider is healthy again.
    - DSM for retry and DLT throughput on the notification pathways.

    Once the provider has recovered, run **Redrive Undelivered Notifications** (or `scripts/demo-control <kind|eks> redrive`). Notifications are idempotent by notification_id, so redrive never duplicates a push.
  EOT

  monitor_thresholds {
    critical = 0
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:exception-management-service", "purpose:learning-demo"]
}

resource "datadog_monitor" "buying_power_reflection_lag" {
  name    = "[Brokerage Demo] Buying-power reflection lag"
  type    = "query alert"
  query   = "max(last_5m):max:data_streams.kafka.lag_messages{env:${var.environment},topic:brokerage.executions.v1,consumer_group:buying-power-service-v1} > 200"
  message = <<-EOT
    `buying-power-service` is falling behind on `brokerage.executions.v1`. Executions are confirmed at the exchange, but sale proceeds and released reservations are not yet reflected in buying power (買付余力). Customers who sell and immediately buy with the proceeds are rejected with INSUFFICIENT_BUYING_POWER — 「売ったのに買えない」.

    Check:
    - Dashboard → Business Flow & Market → Rejects by reason and switch-trade results, and Unreflected buying-power executions.
    - DSM consumer lag and pathway latency for `buying-power-service-v1`, then `buying-power-service` APM traces for slow spans.
    - Whether this follows an opening rush (寄付き集中) burst, which drains on its own, or an injected delay, which needs **Reset to Baseline**.
  EOT

  monitor_thresholds {
    critical = 200
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:buying-power-service", "purpose:learning-demo"]
}

resource "datadog_monitor" "buying_power_reflection_delay" {
  name    = "[Brokerage Demo] Buying-power reflection delay p95"
  type    = "query alert"
  query   = "percentile(last_5m):p95:demo.brokerage.buying_power.reflection_delay{env:${var.environment}} > 5000"
  message = <<-EOT
    The 95th-percentile time from execution to buying-power reflection exceeded 5 seconds. Until reflection completes, sale proceeds cannot be reused for new BUY orders, so switch trades are rejected for insufficient buying power — 「売ったのに買えない」.

    Check:
    - Dashboard → Executive Summary → Oldest unreflected execution and Buying-power reflection delay p95.
    - DSM lag on `brokerage.executions.v1` for `buying-power-service-v1` to separate queueing time from processing time.
    - `buying-power-service` APM latency and its PostgreSQL spans.
  EOT

  monitor_thresholds {
    critical = 5000
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:buying-power-service", "purpose:learning-demo"]
}
