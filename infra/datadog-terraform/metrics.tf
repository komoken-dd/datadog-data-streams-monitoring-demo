resource "datadog_metric_tag_configuration" "buying_power_reflection_delay" {
  metric_name         = "demo.brokerage.buying_power.reflection_delay"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["env", "service", "side", "source", "version"]
}

resource "datadog_metric_metadata" "buying_power_reflection_delay" {
  metric      = "demo.brokerage.buying_power.reflection_delay"
  type        = "distribution"
  short_name  = "買付余力 反映遅延"
  description = "架空の取引所での約定または失効から、口座の買付余力に反映されるまでの経過時間。"
  unit        = "millisecond"
}

resource "datadog_metric_tag_configuration" "notification_delivery_delay" {
  metric_name         = "demo.brokerage.notifications.delivery_delay"
  metric_type         = "distribution"
  include_percentiles = true
  tags                = ["env", "notification_type", "redriven", "service", "version"]
}

resource "datadog_metric_metadata" "notification_delivery_delay" {
  metric      = "demo.brokerage.notifications.delivery_delay"
  type        = "distribution"
  short_name  = "通知 配信遅延"
  description = "約定または失効から、プッシュ通知事業者がお客様向け通知を受理するまでの経過時間（再試行を含む）。"
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
  short_name  = "発注から初回約定まで"
  description = "注文受付から最初の約定までの経過時間。寄付きの板寄せを待つ注文は、寄付き前の待ち時間がここに表れます。"
  unit        = "millisecond"
}

resource "datadog_metric_metadata" "execution_amount" {
  metric      = "demo.brokerage.executions.amount_jpy"
  type        = "distribution"
  short_name  = "約定代金"
  description = "約定ごとの架空の約定代金（約定数量 × 約定単価）。手数料はゼロです。"
  unit        = "yen"
}

resource "datadog_metric_metadata" "buying_power_oldest_unreflected_age" {
  metric      = "demo.brokerage.buying_power.oldest_unreflected_age"
  type        = "gauge"
  short_name  = "最古の未反映約定"
  description = "買付余力にまだ反映されていない約定のうち、最も古いものの経過時間。買付余力が最新の場合はゼロです。"
  unit        = "second"
}

resource "datadog_metric_metadata" "notification_dead_letter_oldest_unresolved_age" {
  metric      = "demo.brokerage.notifications.dead_letter.oldest_unresolved_age"
  type        = "gauge"
  short_name  = "最古の未解決通知デッドレター"
  description = "未配信のお客様向け通知のうち、DLT に入ってからの滞留時間が最も長いもの。"
  unit        = "second"
}

resource "datadog_metric_metadata" "orders_working" {
  metric      = "demo.brokerage.orders.working"
  type        = "gauge"
  short_name  = "未約定注文"
  description = "受付済みで、まだ全部約定も失効もしていない注文の現在件数（銘柄・売買区分別）。"
}

resource "datadog_metric_metadata" "orders_unfilled_quantity" {
  metric      = "demo.brokerage.orders.unfilled_quantity"
  type        = "gauge"
  short_name  = "未約定数量（株）"
  description = "未約定の注文に残っている株数の現在値（銘柄・売買区分別）。売り殺到時に SELL の値が増え続ける場合は、ストップ安で約定できない注文が滞留しています。"
}
