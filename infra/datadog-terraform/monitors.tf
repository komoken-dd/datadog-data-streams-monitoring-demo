resource "datadog_monitor" "unresolved_notification_dead_letters" {
  name    = "[証券デモ] 未解決の通知デッドレター"
  type    = "query alert"
  query   = "avg(last_5m):avg:demo.brokerage.notifications.dead_letter.unresolved{env:${var.environment}} > 0"
  message = <<-EOT
    約定通知・失効通知が 5 秒・30 秒の再試行トピックを使い切り、`brokerage.notifications.dlt.v1` に滞留しています。取引自体は記帳済みですが、該当のお客様には注文の約定または失効が通知されていません。

    確認事項:
    - ダッシュボード → 要対応の注文 → 通知 DLT の発生履歴 で、影響を受けた注文と失敗カテゴリを確認する。このリストは解決済みを含む履歴で、現在の未解決一覧ではありません。
    - プッシュゲートウェイの結果（`demo.brokerage.push_gateway.requests` の result 別）で、プッシュ通知事業者が復旧していることを確認する。
    - DSM で通知 Pathway の再試行トピックと DLT のスループットを確認する。

    事業者の復旧後、**未達通知の再送 (Redrive)** を実行してください（または `scripts/demo-control <kind|eks> redrive`）。DLT の減少は再試行トピックへの再投入を示します。配信完了は配信済み通知の増加と注文ログで確認してください。事業者が停止したままだと再び DLT に入ります。
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
  name    = "[証券デモ] 買付余力反映のコンシューマ lag"
  type    = "query alert"
  query   = "max(last_5m):max:data_streams.kafka.lag_messages{env:${var.environment},topic:brokerage.executions.v1,consumer_group:buying-power-service-v1} > 100"
  message = <<-EOT
    `buying-power-service` が `brokerage.executions.v1` の処理に遅れています。取引所では約定済みですが、売却代金や拘束解除がまだ買付余力に反映されていません。売却直後にその代金で買付しようとするお客様は INSUFFICIENT_BUYING_POWER で拒否されます（「売ったのに買えない」）。

    確認事項:
    - ダッシュボード → 業務フローと市況 → 拒否理由別と乗り換え取引の結果、および エグゼクティブサマリー → 買付余力 未反映約定。
    - `buying-power-service-v1` の DSM Consumer lag と Pathway レイテンシー、続いて `buying-power-service` の APM トレースで遅いスパンを確認する。
    - 寄付き集中による一時的な急増（自然に解消）か、注入された遅延（**ベースラインに戻す** が必要）かを見極める。
  EOT

  monitor_thresholds {
    critical = 100
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:buying-power-service", "purpose:learning-demo"]
}

resource "datadog_monitor" "buying_power_reflection_delay" {
  name    = "[証券デモ] 買付余力 反映遅延 p95"
  type    = "query alert"
  query   = "percentile(last_5m):p95:demo.brokerage.buying_power.reflection_delay{env:${var.environment}} > 5000"
  message = <<-EOT
    約定から買付余力に反映されるまでの時間の 95 パーセンタイルが 5 秒を超えました。反映が完了するまで売却代金を新たな買付に充てられないため、乗り換え取引が買付余力不足で拒否されます（「売ったのに買えない」）。

    確認事項:
    - ダッシュボード → エグゼクティブサマリー → 最古の未反映約定、買付余力 反映遅延 · p95。
    - `buying-power-service-v1` の `brokerage.executions.v1` における DSM lag で、キュー待ち時間と処理時間を切り分ける。
    - `buying-power-service` の APM レイテンシーと PostgreSQL スパンを確認する。
  EOT

  monitor_thresholds {
    critical = 5000
  }

  include_tags        = true
  require_full_window = false
  notify_no_data      = false
  tags                = ["env:${var.environment}", "service:buying-power-service", "purpose:learning-demo"]
}
