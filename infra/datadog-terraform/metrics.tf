resource "datadog_metric_tag_configuration" "order_to_execution_duration" {
  metric_name         = "demo.fx.order_to_execution.duration"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["currency_pair", "env", "service", "version"]
}

resource "datadog_metric_metadata" "order_to_execution_duration" {
  metric      = "demo.fx.order_to_execution.duration"
  type        = "distribution"
  short_name  = "FX order-to-execution duration"
  description = "Elapsed time from generated fictional FX order submission to execution."
  unit        = "millisecond"
}

resource "datadog_metric_metadata" "oldest_active_processing_age" {
  metric      = "demo.fx.orders.oldest_pending_age"
  type        = "gauge"
  short_name  = "Oldest active processing order"
  description = "Age of the oldest actively processing FX order, excluding unresolved dead letters."
  unit        = "second"
}

resource "datadog_metric_metadata" "oldest_unresolved_dead_letter_age" {
  metric      = "demo.fx.dead_letter.oldest_unresolved_age"
  type        = "gauge"
  short_name  = "Oldest unresolved FX dead letter"
  description = "Residence time of the oldest unresolved FX dead letter since it reached the DLT."
  unit        = "second"
}

resource "datadog_metric_tag_configuration" "execution_notional" {
  metric_name         = "demo.fx.executions.notional_usd"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["currency_pair", "env", "service", "side", "version"]
}

resource "datadog_metric_tag_configuration" "execution_synthetic_fee" {
  metric_name         = "demo.fx.executions.synthetic_fee_usd"
  metric_type         = "distribution"
  include_percentiles = false
  tags                = ["currency_pair", "env", "service", "side", "version"]
}
