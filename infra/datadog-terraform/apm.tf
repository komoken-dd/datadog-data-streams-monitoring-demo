resource "datadog_apm_retention_filter" "brokerage_demo_orders" {
  name        = "[証券デモ] 注文トレースを検索可能な状態で保持"
  enabled     = true
  filter_type = "spans-sampling-processor"
  rate        = "1.0"
  trace_rate  = "1.0"

  filter {
    query = "env:${var.environment} AND @order_id:*"
  }
}
