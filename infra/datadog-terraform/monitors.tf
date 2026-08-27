resource "datadog_monitor" "unresolved_dead_letters" {
  name    = "[FX Demo] Unresolved dead letters"
  type    = "query alert"
  query   = "avg(last_5m):avg:demo.fx.dead_letter.unresolved{env:${var.environment}} > 0"
  message = "The demo pipeline has unresolved dead letters. Inspect the affected orders, then use the redrive workflow to resubmit all unresolved records."

  monitor_thresholds {
    critical = 0
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:dead-letter-service", "purpose:learning-demo"]
}

resource "datadog_monitor" "execution_consumer_lag" {
  name    = "[FX Demo] Execution consumer lag"
  type    = "query alert"
  query   = "max(last_5m):max:data_streams.kafka.lag_messages{env:${var.environment},topic:fx.orders.approved.v1} by {consumer_group} > 100"
  message = "Execution lag is high. Correlate DSM pathway latency with execution-adapter APM and the pending-order business metrics."

  monitor_thresholds {
    critical = 100
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:execution-adapter", "purpose:learning-demo"]
}
