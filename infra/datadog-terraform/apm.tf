resource "datadog_apm_retention_filter" "brokerage_demo_orders" {
  name        = "[Brokerage Demo] Retain searchable order traces"
  enabled     = true
  filter_type = "spans-sampling-processor"
  rate        = "1.0"
  trace_rate  = "1.0"

  filter {
    query = "env:${var.environment} AND @order_id:*"
  }
}
