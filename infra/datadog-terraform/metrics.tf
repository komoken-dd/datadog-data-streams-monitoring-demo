resource "datadog_metric_tag_configuration" "buying_power_reflection_delay" {
  metric_name         = "demo.brokerage.buying_power.reflection_delay"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["env", "service", "side", "source", "version"]
}

resource "datadog_metric_metadata" "buying_power_reflection_delay" {
  metric      = "demo.brokerage.buying_power.reflection_delay"
  type        = "distribution"
  short_name  = "Buying-power reflection delay"
  description = "Elapsed time from a fictional exchange execution or expiry to its reflection in the account's buying power (買付余力)."
  unit        = "millisecond"
}

resource "datadog_metric_tag_configuration" "notification_delivery_delay" {
  metric_name         = "demo.brokerage.notifications.delivery_delay"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["env", "notification_type", "service", "version"]
}

resource "datadog_metric_metadata" "notification_delivery_delay" {
  metric      = "demo.brokerage.notifications.delivery_delay"
  type        = "distribution"
  short_name  = "Notification delivery delay"
  description = "Elapsed time from an execution or expiry to acceptance of the customer notification by the push provider, including retries."
  unit        = "millisecond"
}

resource "datadog_metric_tag_configuration" "order_to_first_execution_duration" {
  metric_name         = "demo.brokerage.order_to_first_execution.duration"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["env", "service", "symbol", "version"]
}

resource "datadog_metric_metadata" "order_to_first_execution_duration" {
  metric      = "demo.brokerage.order_to_first_execution.duration"
  type        = "distribution"
  short_name  = "Order to first execution"
  description = "Elapsed time from order acceptance to the first fill. Orders held for the opening auction (板寄せ) show the pre-open wait here."
  unit        = "millisecond"
}

resource "datadog_metric_tag_configuration" "execution_amount" {
  metric_name         = "demo.brokerage.executions.amount_jpy"
  metric_type         = "distribution"
  include_percentiles = false
  tags                = ["env", "service", "side", "symbol", "version"]
}

resource "datadog_metric_metadata" "execution_amount" {
  metric      = "demo.brokerage.executions.amount_jpy"
  type        = "distribution"
  short_name  = "Executed amount"
  description = "Fictional executed amount (約定代金) per fill: executed quantity times execution price. Commission is zero."
  unit        = "yen"
}

resource "datadog_metric_metadata" "buying_power_oldest_unreflected_age" {
  metric      = "demo.brokerage.buying_power.oldest_unreflected_age"
  type        = "gauge"
  short_name  = "Oldest unreflected execution"
  description = "Age of the oldest execution that has not yet been reflected in buying power. Zero when buying power is up to date."
  unit        = "second"
}

resource "datadog_metric_metadata" "notification_dead_letter_oldest_unresolved_age" {
  metric      = "demo.brokerage.notifications.dead_letter.oldest_unresolved_age"
  type        = "gauge"
  short_name  = "Oldest unresolved notification dead letter"
  description = "Residence time of the oldest undelivered customer notification since it reached the DLT."
  unit        = "second"
}

resource "datadog_metric_metadata" "orders_working" {
  metric      = "demo.brokerage.orders.working"
  type        = "gauge"
  short_name  = "Working orders"
  description = "Current number of accepted orders that are not yet fully filled or expired, by symbol and side."
}

resource "datadog_metric_metadata" "orders_unfilled_quantity" {
  metric      = "demo.brokerage.orders.unfilled_quantity"
  type        = "gauge"
  short_name  = "Unfilled quantity (shares)"
  description = "Current number of shares still open on working orders, by symbol and side. A growing SELL value during a sell-off indicates orders stuck at limit-down (ストップ安)."
}
