locals {
  # Services that log against an order_id. Used by the lifecycle list so one
  # order can be followed from customer intake to notification.
  lifecycle_services = join(" OR ", [
    "order-flow-simulator",
    "order-service",
    "buying-power-service",
    "exchange-gateway-simulator",
    "holdings-service",
    "trade-report-service",
    "notification-service",
    "push-gateway-simulator",
    "order-state-service",
    "exception-management-service",
  ])

  order_filter = "@order_id:$order_id.value @account_id:$account_id.value"

  workflow_control_widgets = concat(
    [{
      definition = {
        type             = "note"
        content          = "## 証券シナリオの実行\nシナリオの前に **ベースラインに戻す** を実行すると、取引所・買付余力サービス・プッシュ通知事業者が正常な状態で、通常の注文フロー（毎秒 3 件）が開始または再開します。**寄付き集中** と **材料による売り殺到** は自動でベースラインに戻ります。**買付余力の反映遅延** と **プッシュ通知事業者の障害** はリセットするまで継続します。操作後は **デモ概要** タブに戻るか **Data Streams Monitoring** を開いてください。反映まで数十秒かかることがあります。銘柄・口座・金額はすべて架空です。"
        background_color = "blue"
        font_size        = "16"
        text_align       = "left"
        show_tick        = false
      }
    }],
    [for workflow in datadog_workflow_automation.reset_baseline : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "ベースラインに戻す"
        description = "業務影響: 通常の注文フローが再開し、取引所は基準価格でのザラバ（継続売買）に戻り、買付余力は即時に反映され、プッシュ通知も再び配信されます。確認先: デモ概要 → エグゼクティブサマリー。未反映約定と DSM のコンシューマ lag がほぼゼロに戻ります。"
      }
    }],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "注文フロー停止"
        description = "業務影響: 新規注文の発注が止まります。受付済みの注文は引き続き約定し、買付余力を更新し、お客様に通知されます。確認先: デモ概要 → 受付注文の増加が止まり、未約定注文が減っていきます。パイプラインが空になるにつれて DSM のスループットも低下します。"
      }
    } if name == "stop_generation"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "寄付き集中 · 60秒"
        description = "業務影響: 寄付き前の注文が毎秒 25 件で 60 秒間たまり、板寄せで一斉に約定します。確認先: 未約定注文が増えた後に急減し、発注から初回約定までの p95 が跳ね上がり、brokerage.executions.v1 の DSM lag が 5 つのコンシューマすべてで急増します。"
      }
    } if name == "opening_rush"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "材料による売り殺到 · D105"
        description = "業務影響: 架空銘柄 D105 に 120 秒間売り注文が殺到して株価がストップ安まで下落し、約定しない売り注文が積み上がって最終的に失効します。確認先: 株価とストップ安フラグ、売り未約定数量、失効注文。"
      }
    } if name == "sell_off"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "買付余力の反映遅延"
        description = "業務影響: 売却代金の買付余力への反映が遅れ、売却直後に買付しようとするお客様の注文が拒否されます（「売ったのに買えない」）。確認先: 未反映約定、反映遅延 p95、INSUFFICIENT_BUYING_POWER による拒否。buying-power-service-v1 の DSM lag が増加します。"
      }
    } if name == "buying_power_delay"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "プッシュ通知事業者の障害"
        description = "業務影響: 約定は記帳されますが、約定通知・失効通知がお客様に届かなくなります。通知は 5 秒後と 30 秒後に再試行され、その後 DLT に移ります。確認先: プッシュゲートウェイの結果、通知配信遅延、未解決の通知デッドレター。DSM には再試行と DLT のトラフィックが表示されます。"
      }
    } if name == "push_outage"],
    [for workflow in datadog_workflow_automation.redrive : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "未達通知の再送 (Redrive)"
        description = "業務影響: 未解決の通知をすべて再送します。通知 ID によりプッシュは冪等なため、同じお客様に二重に通知されることはありません。**ベースラインに戻す** でプッシュ通知事業者が復旧してから実行してください。確認先: 未解決の通知デッドレターがゼロに戻り、配信済み通知が増加します。"
      }
    }],
  )

  cli_control_widgets = [{
    definition = {
      type             = "note"
      content          = "## この環境ではデモ操作に CLI を使用します\nWorkflow Automation は無効です。リポジトリで `scripts/demo-control <kind|eks> <action>` を実行し、action には `baseline`、`stop`、`opening-rush`、`sell-off`、`buying-power-delay`、`push-outage`、`redrive` のいずれかを指定します。スクリプトは Kubernetes コンテキストを確認したうえで、一時的な port-forward 経由で ClusterIP のみのエンドポイントにアクセスします。各操作後は **デモ概要** タブまたは **Data Streams Monitoring** に戻ってください。反映まで数十秒かかることがあります。ここにボタンを追加するには `docs/private-action-runner.md` の手順で Private Action Runner を設定し、`enable_workflows = true` を指定してください。"
      background_color = "blue"
      font_size        = "16"
      text_align       = "left"
      show_tick        = false
    }
  }]

  # Encode each branch before selection so Terraform does not coerce the note
  # widget's boolean fields while unifying the two collection types.
  demo_control_widgets = jsondecode(
    var.enable_workflows ? jsonencode(local.workflow_control_widgets) : jsonencode(local.cli_control_widgets)
  )
  demo_control_widget_count = var.enable_workflows ? length(local.workflow_control_widgets) : length(local.cli_control_widgets)

  demo_overview_intro_widget = {
    definition = {
      type             = "note"
      content          = "## ネット証券 注文フローのオブザーバビリティ\n上から順に、お客様への影響（注文の拒否、買付余力の反映遅れ、通知の未達）を検知し、影響を受けた注文・口座を特定し、原因となるストリームやサービスを診断します。お客様の注文は受付時に買付余力を同期的に拘束します。約定は Kafka 経由で買付余力・保有残高・取引報告書・通知・注文照会へファンアウトされます。タイトルに **現在** とあるウィジェットは現時点の状態、それ以外は選択期間内のイベント件数です。銘柄 D101–D105、口座、価格、金額（円）はすべて架空です。[Data Streams Monitoring を開く](${var.datadog_ui_base_url}/data-streams)"
      background_color = "blue"
      font_size        = "16"
      text_align       = "left"
      show_tick        = false
    }
  }

  executive_summary_widgets = [
    {
      definition = {
        type  = "query_value"
        title = "受付注文 · 選択期間"
        requests = [{
          q          = "sum:demo.brokerage.orders.accepted{env:$env.value}.as_count()"
          aggregator = "sum"
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "拒否注文 · 選択期間"
        description = "受付時に同期的に拒否された注文（買付余力不足、売付可能数量不足など）。拒否された注文は取引所に送られません。"
        requests = [{
          q          = "sum:demo.brokerage.orders.rejected{env:$env.value}.as_count()"
          aggregator = "sum"
          conditional_formats = [
            { comparator = "=", value = 0, palette = "white_on_green" },
            { comparator = ">", value = 0, palette = "white_on_yellow" },
          ]
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type  = "toplist"
        title = "拒否理由別 · 選択期間"
        requests = [{
          q = "top(sum:demo.brokerage.orders.rejected{env:$env.value} by {reason}.as_count(), 5, 'sum', 'desc')"
          conditional_formats = [
            { comparator = ">", value = 0, palette = "white_on_red" },
          ]
        }]
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "約定件数 · 選択期間"
        description = "取引所から通知された約定。一部約定も 1 件ずつ数えます。"
        requests = [{
          q          = "sum:demo.brokerage.executions.count{env:$env.value}.as_count()"
          aggregator = "sum"
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "約定代金 · 百万円"
        description = "選択期間内の買付・売付の約定代金の総額（相殺なし）。単位は架空の百万円で、手数料はゼロです。"
        requests = [{
          q          = "sum:demo.brokerage.executions.amount_jpy{env:$env.value} / 1000000"
          aggregator = "sum"
        }]
        autoscale   = false
        custom_unit = "百万円"
        precision   = 1
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "未約定注文 · 現在"
        description = "受付済みで、まだ全部約定も失効もしていない注文。"
        requests = [{
          q          = "sum:demo.brokerage.orders.working{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 50, palette = "white_on_green" },
            { comparator = ">", value = 200, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "売り未約定数量 · 現在"
        description = "未約定の売り注文に残っている株数。売り殺到で銘柄がストップ安になり買い手がいなくなると急増します。"
        requests = [{
          q          = "sum:demo.brokerage.orders.unfilled_quantity{env:$env.value,side:sell}"
          aggregator = "last"
        }]
        autoscale   = true
        custom_unit = "株"
        precision   = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "実行中のシナリオ · 現在"
        description = "order-flow-simulator が実行中のシナリオ: -1 = 停止、0 = ベースライン、1 = 寄付き集中、2 = 材料による売り殺到。"
        requests = [{
          q          = "max:demo.brokerage.scenario.current{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<", value = 0, palette = "white_on_gray" },
            { comparator = "=", value = 0, palette = "white_on_green" },
            { comparator = ">=", value = 1, palette = "white_on_yellow" },
          ]
        }]
        autoscale = false
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "買付余力 未反映約定 · 現在"
        description = "取引所で約定済みだが、まだ買付余力に反映されていない約定。"
        requests = [{
          q          = "sum:demo.brokerage.buying_power.unreflected_executions{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 20, palette = "white_on_green" },
            { comparator = ">", value = 100, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "最古の未反映約定 · 現在"
        description = "買付余力に未反映の約定のうち、最も古いものの経過時間（秒）。増加している間は売却代金を新たな買付に充てられません。"
        requests = [{
          q          = "max:demo.brokerage.buying_power.oldest_unreflected_age{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 2, palette = "white_on_green" },
            { comparator = ">", value = 10, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 1
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "買付余力 反映遅延 · p95"
        description = "選択期間における、約定から買付余力に反映されるまでの時間の 95 パーセンタイル（ms）。"
        requests = [{
          q          = "p95:demo.brokerage.buying_power.reflection_delay{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 1000, palette = "white_on_green" },
            { comparator = ">", value = 5000, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 1
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "通知 配信遅延 · p95"
        description = "約定または失効からプッシュ配信までの時間の 95 パーセンタイル（再試行を含む、ms）。"
        requests = [{
          q          = "p95:demo.brokerage.notifications.delivery_delay{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 2000, palette = "white_on_green" },
            { comparator = ">", value = 10000, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 1
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "未解決の通知デッドレター · 現在"
        description = "再試行を使い切り、exception-management-service で再送 (Redrive) を待っているお客様向け通知。"
        requests = [{
          q          = "sum:demo.brokerage.notifications.dead_letter.unresolved{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "=", value = 0, palette = "white_on_green" },
            { comparator = ">", value = 0, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "最古の未解決通知デッドレター · 現在"
        description = "未配信の通知のうち、DLT に入ってからの滞留時間が最も長いもの（秒）。"
        requests = [{
          q          = "max:demo.brokerage.notifications.dead_letter.oldest_unresolved_age{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "=", value = 0, palette = "white_on_green" },
            { comparator = ">", value = 0, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 1
      }
    },
  ]

  business_flow_widgets = [
    {
      definition = {
        type        = "timeseries"
        title       = "注文フロー · 受付・約定・失効・拒否"
        show_legend = true
        requests = [
          { q = "sum:demo.brokerage.orders.accepted{env:$env.value}.as_count()", display_type = "bars", style = { palette = "cool", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.brokerage.executions.count{env:$env.value}.as_count()", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.brokerage.orders.expired{env:$env.value}.as_count()", display_type = "line", style = { palette = "purple", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.brokerage.orders.rejected{env:$env.value}.as_count()", display_type = "line", style = { palette = "warm", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "sunburst"
        title       = "受付注文 · 売買区分と銘柄"
        description = "内側: 買い / 売り。外側: 売買区分ごとの架空銘柄。値は選択期間内の受付注文件数です。"
        hide_total  = false
        legend = {
          type         = "automatic"
          hide_value   = false
          hide_percent = false
        }
        requests = [{
          queries = [{
            data_source = "metrics"
            name        = "query1"
            query       = "sum:demo.brokerage.orders.accepted{env:$env.value} by {side,symbol}.as_count()"
            aggregator  = "sum"
          }]
          formulas        = [{ formula = "query1" }]
          response_format = "scalar"
          style           = { palette = "dog_classic" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "約定 · 一部約定と全部約定"
        description = "1 つの注文が複数回に分かれて約定（一部約定）することがあります。最後の約定で注文が完了します。"
        show_legend = true
        requests = [{
          q            = "sum:demo.brokerage.executions.count{env:$env.value} by {fill_type}.as_count()"
          display_type = "bars"
          style        = { palette = "green", line_type = "solid", line_width = "normal" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "約定代金 · 銘柄と売買区分"
        show_legend = true
        requests = [{
          q            = "sum:demo.brokerage.executions.amount_jpy{env:$env.value} by {symbol,side}"
          display_type = "bars"
          style        = { palette = "dog_classic", line_type = "solid", line_width = "normal" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "株価 · 銘柄別（右軸: 騰落率 %）"
        show_legend = true
        requests = [
          { q = "avg:demo.brokerage.market.price{env:$env.value} by {symbol}", display_type = "line", style = { palette = "cool", line_type = "solid", line_width = "thick" } },
          { q = "avg:demo.brokerage.market.price_change_pct{env:$env.value} by {symbol}", display_type = "line", style = { palette = "orange", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "query_table"
        title       = "銘柄スナップショット · 現在値・騰落率・ストップ安フラグ"
        description = "銘柄ごとの最新の架空株価。ストップ安フラグが 1 の場合、株価が値幅制限の下限に達しており、売り注文は約定できません。"
        requests = [{
          response_format = "scalar"
          queries = [
            { data_source = "metrics", name = "price", query = "avg:demo.brokerage.market.price{env:$env.value} by {symbol}", aggregator = "last" },
            { data_source = "metrics", name = "change", query = "avg:demo.brokerage.market.price_change_pct{env:$env.value} by {symbol}", aggregator = "last" },
            { data_source = "metrics", name = "limit_down", query = "max:demo.brokerage.market.limit_down{env:$env.value} by {symbol}", aggregator = "last" },
          ]
          formulas = [
            { formula = "price", alias = "現在値 (円)" },
            {
              formula = "change"
              alias   = "騰落率 %"
              conditional_formats = [
                { comparator = "<=", value = -10, palette = "white_on_red" },
                { comparator = "<", value = 0, palette = "white_on_yellow" },
                { comparator = ">=", value = 0, palette = "white_on_green" },
              ]
            },
            {
              formula = "limit_down"
              alias   = "ストップ安"
              conditional_formats = [
                { comparator = ">=", value = 1, palette = "white_on_red" },
                { comparator = "<", value = 1, palette = "white_on_green" },
              ]
            },
          ]
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "未約定注文と未約定数量 · 銘柄と売買区分（現在）"
        description = "実線: 未約定注文の件数。破線・右軸: 未約定株数。"
        show_legend = true
        requests = [
          { q = "sum:demo.brokerage.orders.working{env:$env.value} by {symbol,side}", display_type = "line", style = { palette = "cool", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.brokerage.orders.unfilled_quantity{env:$env.value} by {symbol,side}", display_type = "line", style = { palette = "purple", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "発注から初回約定まで · p50 / p95 / p99"
        description = "寄付きの板寄せを待つ注文は板寄せまで約定しないため、寄付き集中時に p95 と p99 が跳ね上がります。"
        show_legend = true
        requests = [
          { q = "p50:demo.brokerage.order_to_first_execution.duration{env:$env.value}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "normal" } },
          { q = "p95:demo.brokerage.order_to_first_execution.duration{env:$env.value}", display_type = "line", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "p99:demo.brokerage.order_to_first_execution.duration{env:$env.value}", display_type = "line", style = { palette = "warm", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "拒否理由別と乗り換え取引の結果"
        description = "棒: 拒否理由別の件数。赤の太線: INSUFFICIENT_BUYING_POWER（「売ったのに買えない」症状）。破線・右軸: 乗り換え取引（売却し、その代金で買付）の結果別件数。"
        show_legend = true
        requests = [
          { q = "sum:demo.brokerage.orders.rejected{env:$env.value} by {reason}.as_count()", display_type = "bars", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.brokerage.orders.rejected{env:$env.value,reason:insufficient_buying_power}.as_count()", display_type = "line", style = { palette = "red", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.brokerage.simulator.switch_trades{env:$env.value} by {result}.as_count()", display_type = "line", style = { palette = "cool", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "買付余力 反映遅延 · p50 / p95 / p99 と未反映約定"
        description = "破線・右軸: 買付余力に未反映の約定件数（現在）。"
        show_legend = true
        requests = [
          { q = "p50:demo.brokerage.buying_power.reflection_delay{env:$env.value}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "normal" } },
          { q = "p95:demo.brokerage.buying_power.reflection_delay{env:$env.value}", display_type = "line", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "p99:demo.brokerage.buying_power.reflection_delay{env:$env.value}", display_type = "line", style = { palette = "warm", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.brokerage.buying_power.unreflected_executions{env:$env.value}", display_type = "line", style = { palette = "purple", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "通知 · 配信・再試行・デッドレター"
        description = "破線・右軸: 通知配信遅延 p95。"
        show_legend = true
        requests = [
          { q = "sum:demo.brokerage.notifications.delivered{env:$env.value} by {notification_type}.as_count()", display_type = "bars", style = { palette = "green", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.brokerage.notifications.retry_attempts{env:$env.value} by {notification_type}.as_count()", display_type = "line", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.brokerage.notifications.dead_letter.events{env:$env.value} by {failure_category}.as_count()", display_type = "line", style = { palette = "red", line_type = "solid", line_width = "thick" } },
          { q = "p95:demo.brokerage.notifications.delivery_delay{env:$env.value}", display_type = "line", style = { palette = "purple", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "プッシュゲートウェイ · 結果別リクエスト"
        description = "架空の外部プッシュ通知事業者からの応答。事業者の障害はまずここに現れます。"
        show_legend = true
        requests = [{
          q            = "sum:demo.brokerage.push_gateway.requests{env:$env.value} by {result}.as_count()"
          display_type = "bars"
          style        = { palette = "dog_classic", line_type = "solid", line_width = "normal" }
        }]
      }
    },
  ]

  streaming_health_widgets = [
    {
      definition = {
        type        = "timeseries"
        title       = "DSM · Pathway レイテンシー p95"
        show_legend = true
        requests = [{
          q            = "p95:data_streams.latency{env:$env.value} by {pathway_type,start,end}"
          display_type = "line"
          style        = { palette = "dog_classic", line_type = "solid", line_width = "normal" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "DSM · Kafka Consumer lag"
        show_legend = true
        requests = [
          { q = "max:data_streams.kafka.lag_seconds{env:$env.value} by {topic,consumer_group}", display_type = "line", style = { palette = "warm", line_type = "solid", line_width = "thick" } },
          { q = "max:data_streams.kafka.lag_messages{env:$env.value} by {topic,consumer_group}", display_type = "line", style = { palette = "cool", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "DSM · 約定ファンアウトの lag · brokerage.executions.v1"
        description = "コンシューマグループごとに 1 本の線。寄付き集中では全グループが一斉に遅れ、買付余力の反映遅延では buying-power-service-v1 だけが遅れます。"
        show_legend = true
        requests = [{
          q            = "max:data_streams.kafka.lag_messages{env:$env.value,topic:brokerage.executions.v1} by {consumer_group}"
          display_type = "line"
          style        = { palette = "warm", line_type = "solid", line_width = "thick" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "APM · 同期の注文受付（order-service → buying-power-service）"
        description = "棒: HTTP リクエストエラー。線・右軸: p95 レイテンシー。買付余力は注文受付の前に同期処理で拘束されます。"
        show_legend = true
        requests = [
          { q = "sum:trace.servlet.request.errors{env:$env.value,service:order-service} by {service}.as_count()", display_type = "bars", style = { palette = "warm", line_type = "solid", line_width = "normal" } },
          { q = "sum:trace.servlet.request.errors{env:$env.value,service:buying-power-service} by {service}.as_count()", display_type = "bars", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "p95:trace.servlet.request{env:$env.value,service:order-service} by {service}", display_type = "line", style = { palette = "purple", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
          { q = "p95:trace.servlet.request{env:$env.value,service:buying-power-service} by {service}", display_type = "line", style = { palette = "cool", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "APM · Kafka コンシューマのエラーとレイテンシー"
        show_legend = true
        requests = [
          { q = "sum:trace.kafka.consume.errors{env:$env.value} by {service}.as_count()", display_type = "bars", style = { palette = "warm", line_type = "solid", line_width = "normal" } },
          { q = "p95:trace.kafka.consume{env:$env.value} by {service}", display_type = "line", style = { palette = "purple", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Kafka クライアント · プロデューサのエラー・リトライ・リクエストレイテンシー"
        show_legend = true
        requests = [
          { q = "sum:kafka.producer.record.error.rate{env:$env.value} by {service}", display_type = "bars", style = { palette = "warm", line_type = "solid", line_width = "normal" } },
          { q = "sum:kafka.producer.record.retry.rate{env:$env.value} by {service}", display_type = "line", style = { palette = "orange", line_type = "dashed", line_width = "normal" } },
          { q = "avg:kafka.producer.request.latency.avg{env:$env.value} by {service}", display_type = "line", style = { palette = "purple", line_type = "solid", line_width = "thick" }, on_right_yaxis = true },
        ]
      }
    },
  ]

  runtime_health_widgets = [
    {
      definition = {
        type        = "timeseries"
        title       = "Kubernetes 上の Kafka ブローカー · Ready 状態と再起動"
        show_legend = true
        requests = [
          { q = "sum:kubernetes_state.container.ready{kube_namespace:brokerage-demo,kube_app_name:kafka} by {pod_name}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "thick" } },
          { q = "max:kubernetes.containers.restarts{kube_namespace:brokerage-demo,kube_app_name:kafka} by {pod_name}", display_type = "bars", style = { palette = "warm", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Kubernetes 上の Kafka ブローカー · CPU とメモリ"
        show_legend = true
        requests = [
          { q = "avg:kubernetes.cpu.usage.total{kube_namespace:brokerage-demo,kube_app_name:kafka} by {pod_name}", display_type = "line", style = { palette = "cool", line_type = "solid", line_width = "normal" } },
          { q = "avg:kubernetes.memory.working_set{kube_namespace:brokerage-demo,kube_app_name:kafka} by {pod_name}", display_type = "line", style = { palette = "purple", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "アプリケーション Deployment · 希望レプリカ数と Ready レプリカ数"
        show_legend = true
        requests = [
          { q = "max:kubernetes_state.deployment.replicas_desired{env:$env.value,kube_namespace:brokerage-demo} by {kube_deployment}", display_type = "line", style = { palette = "cool", line_type = "dashed", line_width = "normal" } },
          { q = "max:kubernetes_state.deployment.replicas_ready{env:$env.value,kube_namespace:brokerage-demo} by {kube_deployment}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "アプリケーションコンテナ · 再起動"
        show_legend = true
        requests = [{
          q            = "max:kubernetes.containers.restarts{env:$env.value,kube_namespace:brokerage-demo} by {kube_deployment,pod_name}"
          display_type = "bars"
          style        = { palette = "warm", line_type = "solid", line_width = "normal" }
        }]
      }
    },
  ]

  order_investigation_widgets = [
    {
      definition = {
        type             = "note"
        content          = "### 全体の影響から個別の注文へ\n以下のリストはすべて、ダッシュボードのタイムピッカーと **order_id**・**account_id** 変数に連動します。両方を `*` のままにすると影響を受けた注文を探せます。口座を 1 つ選ぶとその口座の全注文を、注文を 1 つ選ぶと受付から約定・買付余力・取引報告書・通知までの流れを追跡できます。**現在** と表示されたゲージは現時点の状態を示すため、古い未解決デッドレターを調べる場合はタイムピッカーの期間を広げてください。"
        background_color = "gray"
        font_size        = "14"
        text_align       = "left"
        show_tick        = false
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "拒否注文 · 選択期間内に受け付けられなかった注文"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value service:order-service ${local.order_filter} @event_type:ORDER_REJECTED"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@account_id" },
              { aggregation = "latest", facet = "@symbol" },
              { aggregation = "latest", facet = "@side" },
              { aggregation = "latest", facet = "@reason" },
              { aggregation = "latest", facet = "@demo_scenario_id" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@account_id:latest", width = "auto" },
            { field = "@symbol:latest", width = "auto" },
            { field = "@side:latest", width = "auto" },
            { field = "@reason:latest", width = "auto" },
            { field = "@demo_scenario_id:latest", width = "auto" },
            { field = "timeline", width = "auto" },
            { field = "max_severity", width = "auto" },
          ]
        }]
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "失効注文 · 選択期間内に未約定数量が失効した注文"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value ${local.order_filter} @event_type:ORDER_EXPIRED"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@account_id" },
              { aggregation = "latest", facet = "@symbol" },
              { aggregation = "latest", facet = "@reason" },
              { aggregation = "latest", facet = "@demo_scenario_id" },
              { aggregation = "count", facet = "count" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@account_id:latest", width = "auto" },
            { field = "@symbol:latest", width = "auto" },
            { field = "@reason:latest", width = "auto" },
            { field = "@demo_scenario_id:latest", width = "auto" },
            { field = "timeline", width = "auto" },
            { field = "max_severity", width = "auto" },
            { field = "count:count", width = "auto" },
          ]
        }]
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "通知デッドレター · まだ通知が届いていないお客様"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value service:exception-management-service ${local.order_filter} @event_type:NOTIFICATION_DEAD_LETTER"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@account_id" },
              { aggregation = "latest", facet = "@symbol" },
              { aggregation = "latest", facet = "@notification_type" },
              { aggregation = "latest", facet = "@failure_category" },
              { aggregation = "latest", facet = "@retry_count" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@account_id:latest", width = "auto" },
            { field = "@symbol:latest", width = "auto" },
            { field = "@notification_type:latest", width = "auto" },
            { field = "@failure_category:latest", width = "auto" },
            { field = "@retry_count:latest", width = "auto" },
            { field = "timeline", width = "auto" },
            { field = "max_severity", width = "auto" },
          ]
        }]
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "注文ライフサイクル · 注文をクリックすると全ステージを確認できます"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value ${local.order_filter} service:(${local.lifecycle_services})"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@account_id" },
              { aggregation = "latest", facet = "@symbol" },
              { aggregation = "latest", facet = "@event_type" },
              { aggregation = "count", facet = "count" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@account_id:latest", width = "auto" },
            { field = "@symbol:latest", width = "auto" },
            { field = "@event_type:latest", width = "auto" },
            { field = "timeline", width = "auto" },
            { field = "duration", width = "auto" },
            { field = "max_severity", width = "auto" },
            { field = "count:count", width = "auto" },
          ]
        }]
      }
    },
  ]

  # Fixed-layout dashboards use a 12-column grid. Keep layout data separate
  # from widget definitions so queries and presentation sizing can be reviewed
  # independently. The explicit layouts also allow GUI drag/resize operations;
  # any accepted GUI changes must subsequently be reflected here before the
  # next Terraform apply.
  workflow_control_widget_layouts = [
    { x = 0, y = 0, width = 12, height = 2 },
    { x = 0, y = 2, width = 6, height = 3 },
    { x = 6, y = 2, width = 6, height = 3 },
    { x = 0, y = 5, width = 6, height = 3 },
    { x = 6, y = 5, width = 6, height = 3 },
    { x = 0, y = 8, width = 6, height = 3 },
    { x = 6, y = 8, width = 6, height = 3 },
    { x = 0, y = 11, width = 6, height = 3 },
  ]

  cli_control_widget_layouts = [
    { x = 0, y = 0, width = 12, height = 5 },
  ]

  demo_control_widget_layouts = var.enable_workflows ? local.workflow_control_widget_layouts : local.cli_control_widget_layouts

  executive_summary_widget_layouts = [
    { x = 0, y = 0, width = 3, height = 2 },
    { x = 3, y = 0, width = 3, height = 2 },
    { x = 6, y = 0, width = 3, height = 2 },
    { x = 9, y = 0, width = 3, height = 2 },
    { x = 0, y = 2, width = 3, height = 2 },
    { x = 3, y = 2, width = 3, height = 2 },
    { x = 6, y = 2, width = 3, height = 2 },
    { x = 9, y = 2, width = 3, height = 2 },
    { x = 0, y = 4, width = 3, height = 2 },
    { x = 3, y = 4, width = 3, height = 2 },
    { x = 6, y = 4, width = 3, height = 2 },
    { x = 9, y = 4, width = 3, height = 2 },
    { x = 0, y = 6, width = 6, height = 2 },
    { x = 6, y = 6, width = 6, height = 2 },
  ]

  business_flow_widget_layouts = [
    for index in range(length(local.business_flow_widgets)) : {
      x      = (index % 2) * 6
      y      = floor(index / 2) * 3
      width  = 6
      height = 3
    }
  ]

  streaming_health_widget_layouts = [
    for index in range(length(local.streaming_health_widgets)) : {
      x      = (index % 2) * 6
      y      = floor(index / 2) * 3
      width  = 6
      height = 3
    }
  ]

  runtime_health_widget_layouts = [
    for index in range(length(local.runtime_health_widgets)) : {
      x      = (index % 2) * 6
      y      = floor(index / 2) * 3
      width  = 6
      height = 3
    }
  ]

  order_investigation_widget_layouts = [
    { x = 0, y = 0, width = 12, height = 2 },
    { x = 0, y = 2, width = 6, height = 3 },
    { x = 6, y = 2, width = 6, height = 3 },
    { x = 0, y = 5, width = 6, height = 3 },
    { x = 6, y = 5, width = 6, height = 3 },
  ]

  fixed_demo_control_widgets = [
    for index, widget in local.demo_control_widgets : merge(widget, {
      layout = local.demo_control_widget_layouts[index]
    })
  ]

  fixed_executive_summary_widgets = [
    for index, widget in local.executive_summary_widgets : merge(widget, {
      layout = local.executive_summary_widget_layouts[index]
    })
  ]

  fixed_business_flow_widgets = [
    for index, widget in local.business_flow_widgets : merge(widget, {
      layout = local.business_flow_widget_layouts[index]
    })
  ]

  fixed_streaming_health_widgets = [
    for index, widget in local.streaming_health_widgets : merge(widget, {
      layout = local.streaming_health_widget_layouts[index]
    })
  ]

  fixed_runtime_health_widgets = [
    for index, widget in local.runtime_health_widgets : merge(widget, {
      layout = local.runtime_health_widget_layouts[index]
    })
  ]

  fixed_order_investigation_widgets = [
    for index, widget in local.order_investigation_widgets : merge(widget, {
      layout = local.order_investigation_widget_layouts[index]
    })
  ]

  demo_overview_group_widgets = [
    {
      definition = {
        type             = "group"
        title            = "1 · エグゼクティブサマリー · お客様への影響を一目で把握"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_executive_summary_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "2 · 業務フローと市況 · 注文・約定・株価・買付余力・通知"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_business_flow_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "3 · 要対応の注文 · 注文または口座を選んでライフサイクルを確認"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_order_investigation_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "4 · ストリーミングとアプリケーションの診断 · DSM・Kafka クライアント・APM"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_streaming_health_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "5 · Kafka と Kubernetes のランタイム · 補助的な診断"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_runtime_health_widgets
      }
    },
  ]

  # Group height = widget rows + 2 for the group title.
  demo_overview_group_layouts = [
    { x = 0, y = 2, width = 12, height = 10 },
    { x = 0, y = 12, width = 12, height = 20 },
    { x = 0, y = 32, width = 12, height = 10 },
    { x = 0, y = 42, width = 12, height = 11 },
    { x = 0, y = 53, width = 12, height = 8 },
  ]

  fixed_demo_overview_group_widgets = [
    for index, widget in local.demo_overview_group_widgets : merge(widget, {
      layout = local.demo_overview_group_layouts[index]
    })
  ]

  fixed_demo_overview_intro_widget = merge(local.demo_overview_intro_widget, {
    layout = { x = 0, y = 0, width = 12, height = 2 }
  })

  # Keep control widget IDs stable for the dedicated tab. Within Demo Overview,
  # store the introduction before the grouped widgets so its reading order is
  # unambiguous in both the Terraform source and the Datadog API representation.
  dashboard_widgets = concat(
    local.fixed_demo_control_widgets,
    [local.fixed_demo_overview_intro_widget],
    local.fixed_demo_overview_group_widgets,
  )

  demo_overview_tab_widget_ids = concat(
    ["@${local.demo_control_widget_count + 1}"],
    [for index in range(length(local.demo_overview_group_widgets)) : "@${local.demo_control_widget_count + index + 2}"],
  )

  dashboard_tabs = [
    {
      id         = "614099eb-6e19-4f93-a2db-45eb0bf48d3d"
      name       = "デモ概要"
      widget_ids = local.demo_overview_tab_widget_ids
    },
    {
      id         = "1f40d12a-f88f-49e2-b04e-f90fe96daf50"
      name       = "デモ操作"
      widget_ids = [for index in range(local.demo_control_widget_count) : "@${index + 1}"]
    },
  ]
}

resource "datadog_dashboard_json" "brokerage_demo" {
  dashboard = jsonencode({
    title       = "ネット証券 注文フロー · Datadog Data Streams Monitoring デモ"
    description = var.enable_workflows ? "架空のネット証券における現物株式の注文フロー。お客様への影響の検知、個別の注文・口座へのドリルダウン、原因となるストリームやサービスの診断、復旧の確認を「デモ概要」タブで行います。シナリオ実行用の Workflow は「デモ操作」タブにあります。" : "架空のネット証券における現物株式の注文フロー。お客様への影響の検知、個別の注文・口座へのドリルダウン、原因となるストリームやサービスの診断、復旧の確認を「デモ概要」タブで行います。「デモ操作」タブでは CLI による代替手順を説明しています。"
    layout_type = "ordered"
    reflow_type = "fixed"
    notify_list = null
    template_variables = [
      {
        name             = "env"
        prefix           = "env"
        available_values = [var.environment]
        defaults         = [var.environment]
      },
      {
        name     = "order_id"
        prefix   = "@order_id"
        defaults = ["*"]
      },
      {
        name     = "account_id"
        prefix   = "@account_id"
        defaults = ["*"]
      },
    ]
    tabs    = local.dashboard_tabs
    widgets = local.dashboard_widgets
  })
}
