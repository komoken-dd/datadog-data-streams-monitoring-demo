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
        content          = "## Run brokerage scenarios\nRun **Reset to Baseline** before a scenario to start or resume normal order flow (3 orders/s) with a healthy exchange, buying-power service, and push provider. **Opening Rush** and **Sell-Off** return to baseline automatically; **Buying-Power Delay** and **Push Provider Outage** stay active until you reset. After an action, return to **Demo Overview** or open **Data Streams Monitoring**. Changes can take tens of seconds to appear. All symbols, accounts, and amounts are fictional."
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
        title       = "Reset to Baseline"
        description = "Business effect: baseline order flow resumes, the exchange returns to a continuous session at base prices, buying power is reflected promptly, and push notifications are delivered again. Observe: Demo Overview → Executive Summary; unreflected executions and DSM lag fall back to near zero."
      }
    }],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Stop Order Flow"
        description = "Business effect: no new customer orders are submitted; accepted orders still execute, update buying power, and notify customers. Observe: Demo Overview → Orders accepted stops rising and Working orders drains; DSM throughput falls as the pipeline empties."
      }
    } if name == "stop_generation"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Opening Rush (寄付き集中) 60s"
        description = "Business effect: pre-open orders accumulate for 60 seconds at 25 orders/s, then the opening auction (板寄せ) fills them in one burst. Observe: Working orders climbs then drops, Order-to-first-execution p95 jumps, and DSM lag on brokerage.executions.v1 spikes across all five consumers."
      }
    } if name == "opening_rush"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "News-Driven Sell-Off on D105 (材料による売り殺到)"
        description = "Business effect: sell orders for fictional symbol D105 surge for 120 seconds, the price falls to limit-down (ストップ安), and unfilled sell orders pile up and finally expire. Observe: Market price and limit-down flag, Unfilled SELL quantity, and Expired orders."
      }
    } if name == "sell_off"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Delay Buying-Power Reflection"
        description = "Business effect: sale proceeds reach buying power (買付余力) late, so customers who sell and immediately buy are rejected — 「売ったのに買えない」. Observe: Unreflected executions, Reflection delay p95, and INSUFFICIENT_BUYING_POWER rejects; DSM lag for buying-power-service-v1 grows."
      }
    } if name == "buying_power_delay"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Push Provider Outage"
        description = "Business effect: trades are booked but customers stop receiving execution and expiry notifications; notifications retry after 5s and 30s, then land in the DLT. Observe: Push gateway results, Notification delivery delay, and Unresolved notification dead letters; DSM shows retry and DLT traffic."
      }
    } if name == "push_outage"],
    [for workflow in datadog_workflow_automation.redrive : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Redrive Undelivered Notifications"
        description = "Business effect: every unresolved notification is sent again; notification IDs make the push idempotent, so no customer is notified twice. Run after Reset to Baseline has recovered the push provider. Observe: Unresolved notification dead letters returns to zero and Notifications delivered rises."
      }
    }],
  )

  cli_control_widgets = [{
    definition = {
      type             = "note"
      content          = "## Operator Controls use the CLI in this deployment\nWorkflow Automation is disabled. From the repository, run `scripts/demo-control <kind|eks> <action>` with one of: `baseline`, `stop`, `opening-rush`, `sell-off`, `buying-power-delay`, `push-outage`, or `redrive`. The script verifies the Kubernetes context and reaches ClusterIP-only endpoints through a temporary port-forward. Return to **Demo Overview** or **Data Streams Monitoring** after each action; changes can take tens of seconds to appear. To add buttons here, follow `docs/private-action-runner.md` and set `enable_workflows = true`."
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
      content          = "## Online brokerage order flow observability\nRead top-to-bottom: detect customer impact (rejected orders, late buying power (買付余力), undelivered notifications), find the affected orders and accounts, then diagnose the responsible stream or service. Customer orders reserve buying power synchronously; executions fan out over Kafka to buying power, holdings, trade reports, notifications, and order inquiry. Widgets labelled **now** are current-state gauges; the others count events in the selected window. Symbols D101–D105, accounts, prices, and amounts (JPY, 円) are fictional. [Open Data Streams Monitoring](${var.datadog_ui_base_url}/data-streams)."
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
        title = "Orders accepted · selected window"
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
        title       = "Orders rejected · selected window"
        description = "Orders rejected synchronously at intake, for example for insufficient buying power or sellable quantity. Rejected orders never reach the exchange."
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
        title = "Rejects by reason · selected window"
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
        title       = "Executions (約定) · selected window"
        description = "Fills reported by the exchange, counting each partial fill separately."
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
        title       = "Executed amount (約定代金) · 百万円"
        description = "Gross BUY and SELL executed amount during the selected window, not netted, in millions of fictional yen (百万円). Commission is zero."
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
        title       = "Working orders · now"
        description = "Accepted orders that are not yet fully filled or expired."
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
        title       = "Unfilled SELL quantity · now"
        description = "Shares still open on working SELL orders. Rises sharply when a sell-off pushes a symbol to limit-down (ストップ安) and buyers disappear."
        requests = [{
          q          = "sum:demo.brokerage.orders.unfilled_quantity{env:$env.value,side:SELL}"
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
        title       = "Current scenario · now"
        description = "Scenario currently driven by order-flow-simulator: -1 = stopped, 0 = baseline, 1 = opening rush (寄付き集中), 2 = news-driven sell-off (材料による売り殺到)."
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
        title       = "Unreflected buying-power executions · now"
        description = "Executions confirmed by the exchange whose effect is not yet reflected in buying power (買付余力)."
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
        title       = "Oldest unreflected execution · now"
        description = "Age of the oldest execution not yet reflected in buying power. While it grows, sale proceeds cannot fund new BUY orders."
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
        title       = "Buying-power reflection delay · p95"
        description = "95th-percentile time from execution to buying-power reflection in the selected window."
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
        title       = "Notification delivery delay · p95"
        description = "95th-percentile time from execution or expiry to push delivery, including retries."
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
        title       = "Unresolved notification dead letters · now"
        description = "Customer notifications that exhausted retries and wait in exception-management-service for redrive."
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
        title       = "Oldest unresolved notification dead letter · now"
        description = "Residence time of the oldest undelivered notification since it reached the DLT."
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
        title       = "Order flow · accepted, executions, expired, rejected"
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
        title       = "Accepted orders · side and symbol"
        description = "Inner ring: BUY/SELL. Outer ring: fictional symbol within each side. Values are accepted order counts for the selected time window."
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
        title       = "Executions · partial versus final fill"
        description = "An order can fill in several parts (分割約定). The final fill completes the order."
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
        title       = "Executed amount (約定代金) · symbol and side"
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
        title       = "Market price · symbol (change % on right axis)"
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
        title       = "Market snapshot · price, change %, and limit-down (ストップ安) flag"
        description = "Latest fictional quote per symbol. A limit-down flag of 1 means the price reached the lower daily limit and sell orders can no longer fill."
        requests = [{
          response_format = "scalar"
          queries = [
            { data_source = "metrics", name = "price", query = "avg:demo.brokerage.market.price{env:$env.value} by {symbol}", aggregator = "last" },
            { data_source = "metrics", name = "change", query = "avg:demo.brokerage.market.price_change_pct{env:$env.value} by {symbol}", aggregator = "last" },
            { data_source = "metrics", name = "limit_down", query = "max:demo.brokerage.market.limit_down{env:$env.value} by {symbol}", aggregator = "last" },
          ]
          formulas = [
            { formula = "price", alias = "Last price (円)" },
            {
              formula = "change"
              alias   = "Change %"
              conditional_formats = [
                { comparator = "<=", value = -10, palette = "white_on_red" },
                { comparator = "<", value = 0, palette = "white_on_yellow" },
                { comparator = ">=", value = 0, palette = "white_on_green" },
              ]
            },
            {
              formula = "limit_down"
              alias   = "Limit-down"
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
        title       = "Working orders and unfilled quantity · symbol and side (now)"
        description = "Lines: working order count. Dashed, right axis: unfilled shares."
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
        title       = "Order to first execution · p50 / p95 / p99"
        description = "Orders held for the opening auction (板寄せ) wait for the auction, so p95 and p99 jump during an opening rush."
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
        title       = "Rejects by reason and switch-trade results"
        description = "Bars: rejects by reason. Thick red line: INSUFFICIENT_BUYING_POWER, the 「売ったのに買えない」 symptom. Dashed, right axis: switch trades (sell, then buy with the proceeds) by result."
        show_legend = true
        requests = [
          { q = "sum:demo.brokerage.orders.rejected{env:$env.value} by {reason}.as_count()", display_type = "bars", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.brokerage.orders.rejected{env:$env.value,reason:INSUFFICIENT_BUYING_POWER}.as_count()", display_type = "line", style = { palette = "red", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.brokerage.simulator.switch_trades{env:$env.value} by {result}.as_count()", display_type = "line", style = { palette = "cool", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Buying-power reflection delay · p50 / p95 / p99 and unreflected executions"
        description = "Dashed, right axis: executions not yet reflected in buying power (now)."
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
        title       = "Notifications · delivered, retried, and dead-lettered"
        description = "Dashed, right axis: notification delivery delay p95."
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
        title       = "Push gateway · requests by result"
        description = "Responses from the fictional external push provider. A provider outage shows up here first."
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
        title       = "DSM · pathway latency p95"
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
        title       = "DSM · Kafka consumer lag"
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
        title       = "DSM · execution fan-out lag on brokerage.executions.v1"
        description = "One line per consumer group. During an opening rush every group lags together; a buying-power delay makes only buying-power-service-v1 fall behind."
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
        title       = "APM · synchronous order intake (order-service → buying-power-service)"
        description = "Bars: HTTP request errors. Lines, right axis: p95 latency. Buying power is reserved in-line before an order is accepted."
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
        title       = "APM · Kafka consumer errors and latency"
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
        title       = "Kafka clients · producer errors, retries, and request latency"
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
        title       = "Kafka brokers on Kubernetes · readiness and restarts"
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
        title       = "Kafka brokers on Kubernetes · CPU and memory"
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
        title       = "Application deployments · desired versus ready replicas"
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
        title       = "Application containers · restarts"
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
        content          = "### From aggregate impact to one order\nAll lists below follow the dashboard Time Picker and the **order_id** and **account_id** variables. Leave both as `*` to find affected orders, then select one account to see every order it placed, or one order to follow it from intake through execution, buying power, trade report, and notification. Gauges marked **now** are current state, so widen the Time Picker when investigating an older unresolved dead letter."
        background_color = "gray"
        font_size        = "14"
        text_align       = "left"
        show_tick        = false
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "Rejected orders · not accepted in selected window"
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
        title       = "Expired orders · unfilled quantity released in selected window"
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
        title       = "Notification dead letters · customers not yet notified"
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
        title       = "Order lifecycle transactions · click one order to inspect every observed stage"
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
        title            = "1 · Executive Summary · customer impact at a glance"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_executive_summary_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "2 · Business Flow & Market · orders, executions, prices, buying power, and notifications"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_business_flow_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "3 · Orders Requiring Attention · open one order or account and inspect its lifecycle"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_order_investigation_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "4 · Streaming & Application Diagnosis · DSM, Kafka clients, and APM"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_streaming_health_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "5 · Kafka & Kubernetes Runtime · supporting diagnosis"
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
      name       = "Demo Overview"
      widget_ids = local.demo_overview_tab_widget_ids
    },
    {
      id         = "1f40d12a-f88f-49e2-b04e-f90fe96daf50"
      name       = "Operator Controls"
      widget_ids = [for index in range(local.demo_control_widget_count) : "@${index + 1}"]
    },
  ]
}

resource "datadog_dashboard_json" "brokerage_demo" {
  dashboard = jsonencode({
    title       = "Online Brokerage Order Flow · Datadog Data Streams Monitoring Demo"
    description = var.enable_workflows ? "Fictional online-brokerage (ネット証券) cash-equity order flow. Detect customer impact, drill down to one order or account, diagnose the responsible stream or service, and verify recovery in one Demo Overview; scenario workflows remain on a separate Operator Controls tab." : "Fictional online-brokerage (ネット証券) cash-equity order flow. Detect customer impact, drill down to one order or account, diagnose the responsible stream or service, and verify recovery in one Demo Overview; the Operator Controls tab explains the CLI fallback."
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
