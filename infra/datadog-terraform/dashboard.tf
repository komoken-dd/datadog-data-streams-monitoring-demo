locals {
  workflow_control_widgets = concat(
    [{
      definition = {
        type             = "note"
        content          = "## Explore business-flow changes\nRun **Reset to Baseline** to start or resume normal traffic before a scenario. Use **Stop Order Generation** when you want to end new order intake. After an action, return to **Demo Overview** or open **Data Streams Monitoring**. Changes can take tens of seconds to appear."
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
        description = "Business effect: returns order intake and execution to normal so queued orders can complete. Observe: Demo Overview → Business Flow, Pending orders, and Order-to-execution duration; DSM lag should fall."
      }
    }],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Stop Order Generation"
        description = "Business effect: stops new orders from entering while accepted orders continue to completion. Observe: Demo Overview → Orders received stops rising and Pending orders drains; DSM throughput falls as the pipeline empties."
      }
    } if name == "stop_generation"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Generate 90-Second Order Surge"
        description = "Business effect: raises order intake from 2 to 20 orders/s for 90 seconds, then returns automatically. Observe: Demo Overview → Orders received and Executions completed; DSM throughput rises and then settles."
      }
    } if name == "generate_surge"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Inject Execution Delay"
        description = "Business effect: approved orders wait longer for execution, increasing pending orders and completion time. Observe: Demo Overview → Pending orders and Order-to-execution duration; DSM pathway latency and lag rise."
      }
    } if name == "inject_delay"],
    [for name, workflow in datadog_workflow_automation.single_step : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Fail Next 10 Orders"
        description = "Business effect: the next 10 approved orders fail after retries and remain unresolved. Observe: Demo Overview → Business exceptions, Unresolved dead letters, and affected-order lists; DSM shows retry and DLT traffic."
      }
    } if name == "inject_failure"],
    [for workflow in datadog_workflow_automation.redrive : {
      definition = {
        type        = "run_workflow"
        workflow_id = workflow.id
        title       = "Redrive All Dead Letters"
        description = "Business effect: all unresolved orders are resubmitted and continue toward execution. Observe: Demo Overview → Unresolved dead letters returns to zero, Executions completed rises, and lifecycle lists show completion."
      }
    }],
  )

  cli_control_widgets = [{
    definition = {
      type             = "note"
      content          = "## Operator Controls use the CLI in this deployment\nWorkflow Automation is disabled. From the repository, run `scripts/demo-control <kind|eks> <action>` with one of: `baseline`, `stop`, `surge`, `delay`, `failure`, or `redrive`. The script verifies the Kubernetes context and reaches ClusterIP-only endpoints through a temporary port-forward. Return to **Demo Overview** or **Data Streams Monitoring** after each action; changes can take tens of seconds to appear. To add buttons here, follow `docs/private-action-runner.md` and set `enable_workflows = true`."
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
      content          = "## FX order lifecycle observability\nRead top-to-bottom: detect a business anomaly, identify orders affected during the selected time range, and inspect one order without leaving **Demo Overview**. Lists and flow widgets follow the dashboard Time Picker. **Pending** and **Unresolved dead letters** are explicitly labelled current-state gauges; other widgets describe events observed in the selected window. Fees are synthetic. [Open Data Streams Monitoring](${var.datadog_ui_base_url}/data-streams)."
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
        title = "Orders received · selected window"
        requests = [{
          q          = "sum:demo.fx.orders.received{env:$env.value}.as_count()"
          aggregator = "sum"
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Executions completed · selected window"
        description = "Completed executions accumulated over the dashboard time window."
        requests = [{
          q          = "sum:demo.fx.executions.completed{env:$env.value}.as_count()"
          aggregator = "sum"
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Gross executed notional · selected window"
        description = "Gross Buy and Sell notional completed during the dashboard time window, converted with fixed synthetic USD rates and not netted. Displayed in billions of USD."
        requests = [{
          q          = "sum:demo.fx.executions.notional_usd{env:$env.value} / 1000000000"
          aggregator = "sum"
        }]
        autoscale   = false
        custom_unit = "B USD"
        precision   = 1
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Realized fees · selected window"
        description = "Fictional fees attached to completed executions during the dashboard time window; not actual revenue or spread."
        requests = [{
          q          = "sum:demo.fx.executions.synthetic_fee_usd{env:$env.value}"
          aggregator = "sum"
        }]
        autoscale   = true
        custom_unit = "USD"
        precision   = 2
      }
    },
    {
      definition = {
        type  = "query_value"
        title = "Pending orders"
        requests = [{
          q          = "avg:demo.fx.orders.pending{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 10, palette = "white_on_green" },
            { comparator = ">", value = 25, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Pending USD-equivalent notional"
        description = "Current USD-equivalent notional for non-terminal orders, including retry and dead-letter states."
        requests = [{
          q          = "avg:demo.fx.orders.pending_notional_usd{env:$env.value}"
          aggregator = "last"
        }]
        autoscale   = true
        custom_unit = "USD"
        precision   = 0
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Expected fees on pending orders"
        description = "Current fictional fee opportunity if all non-terminal orders eventually execute; not realized revenue."
        requests = [{
          q          = "sum:demo.fx.orders.pending_expected_fee_usd{env:$env.value}"
          aggregator = "last"
        }]
        autoscale   = true
        custom_unit = "USD"
        precision   = 2
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Order-to-execution · p95"
        description = "95th-percentile elapsed time from order submission to execution. Datadog automatically selects milliseconds, seconds, or minutes from the registered time unit."
        requests = [{
          q          = "p95:demo.fx.order_to_execution.duration{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 500, palette = "white_on_green" },
            { comparator = ">", value = 2000, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 1
      }
    },
    {
      definition = {
        type        = "query_value"
        title       = "Oldest active processing order"
        description = "Current age of the oldest order that can still progress through normal processing. Unresolved dead letters are excluded and shown separately."
        requests = [{
          q          = "avg:demo.fx.orders.oldest_pending_age{env:$env.value}"
          aggregator = "last"
          conditional_formats = [
            { comparator = "<=", value = 5, palette = "white_on_green" },
            { comparator = ">", value = 30, palette = "white_on_red" },
          ]
        }]
        autoscale = true
        precision = 1
      }
    },
    {
      definition = {
        type  = "query_value"
        title = "Unresolved dead letters"
        requests = [{
          q          = "avg:demo.fx.dead_letter.unresolved{env:$env.value}"
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
        title       = "Oldest unresolved dead letter"
        description = "Current residence time of the oldest unresolved record since it reached the dead-letter topic."
        requests = [{
          q          = "avg:demo.fx.dead_letter.oldest_unresolved_age{env:$env.value}"
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
        title       = "Business flow · received, executed, rejected, DLT"
        show_legend = true
        requests = [
          { q = "sum:demo.fx.orders.received{env:$env.value}.as_count()", display_type = "bars", style = { palette = "cool", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.fx.executions.completed{env:$env.value}.as_count()", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.fx.orders.rejected{env:$env.value}.as_count()", display_type = "line", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.fx.dead_letter.events{env:$env.value}.as_count()", display_type = "line", style = { palette = "warm", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "sunburst"
        title       = "Order intake mix · Buy/Sell and currency pair"
        description = "Inner ring: Buy/Sell. Outer ring: currency pair within each side. Values are order counts for the selected time window."
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
            query       = "sum:demo.fx.orders.received{env:$env.value} by {side,currency_pair}.as_count()"
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
        title       = "Executed USD-equivalent notional · currency pair and side"
        show_legend = true
        requests = [{
          q            = "sum:demo.fx.executions.notional_usd{env:$env.value} by {currency_pair,side}"
          display_type = "bars"
          style        = { palette = "dog_classic", line_type = "solid", line_width = "normal" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Pending backlog snapshot · count and USD-equivalent notional by currency pair"
        show_legend = true
        requests = [
          { q = "sum:demo.fx.orders.pending_by_pair{env:$env.value} by {currency_pair}", display_type = "line", style = { palette = "cool", line_type = "solid", line_width = "thick" } },
          { q = "sum:demo.fx.orders.pending_notional_by_pair_usd{env:$env.value} by {currency_pair}", display_type = "line", style = { palette = "purple", line_type = "dashed", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Fees · realized flow versus current pending opportunity"
        show_legend = true
        requests = [
          { q = "sum:demo.fx.executions.synthetic_fee_usd{env:$env.value} by {currency_pair,side}", display_type = "bars", style = { palette = "green", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.fx.orders.pending_expected_fee_usd{env:$env.value} by {currency_pair}", display_type = "line", style = { palette = "orange", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Order-to-execution duration · p50 / p95 / p99"
        show_legend = true
        requests = [
          { q = "p50:demo.fx.order_to_execution.duration{env:$env.value}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "normal" } },
          { q = "p95:demo.fx.order_to_execution.duration{env:$env.value}", display_type = "line", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "p99:demo.fx.order_to_execution.duration{env:$env.value}", display_type = "line", style = { palette = "warm", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Order-to-execution p95 · currency pair"
        show_legend = true
        requests = [{
          q            = "p95:demo.fx.order_to_execution.duration{env:$env.value} by {currency_pair}"
          display_type = "line"
          style        = { palette = "orange", line_type = "solid", line_width = "thick" }
        }]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Business exceptions · rejection, retry, and dead letter"
        show_legend = true
        requests = [
          { q = "sum:demo.fx.orders.rejected{env:$env.value} by {failure_category,currency_pair}.as_count()", display_type = "bars", style = { palette = "orange", line_type = "solid", line_width = "normal" } },
          { q = "sum:demo.fx.retry.attempts{env:$env.value} by {failure_category,currency_pair}.as_count()", display_type = "line", style = { palette = "warm", line_type = "dashed", line_width = "normal" } },
          { q = "sum:demo.fx.dead_letter.events{env:$env.value} by {failure_category,currency_pair}.as_count()", display_type = "line", style = { palette = "red", line_type = "solid", line_width = "thick" } },
        ]
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
        title       = "APM · service errors and latency"
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
          { q = "sum:kubernetes_state.container.ready{kube_namespace:fx-demo,kube_app_name:kafka} by {pod_name}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "thick" } },
          { q = "max:kubernetes.containers.restarts{kube_namespace:fx-demo,kube_app_name:kafka} by {pod_name}", display_type = "bars", style = { palette = "warm", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Kafka brokers on Kubernetes · CPU and memory"
        show_legend = true
        requests = [
          { q = "avg:kubernetes.cpu.usage.total{kube_namespace:fx-demo,kube_app_name:kafka} by {pod_name}", display_type = "line", style = { palette = "cool", line_type = "solid", line_width = "normal" } },
          { q = "avg:kubernetes.memory.working_set{kube_namespace:fx-demo,kube_app_name:kafka} by {pod_name}", display_type = "line", style = { palette = "purple", line_type = "solid", line_width = "normal" }, on_right_yaxis = true },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Application deployments · desired versus ready replicas"
        show_legend = true
        requests = [
          { q = "max:kubernetes_state.deployment.replicas_desired{env:$env.value,kube_namespace:fx-demo} by {kube_deployment}", display_type = "line", style = { palette = "cool", line_type = "dashed", line_width = "normal" } },
          { q = "max:kubernetes_state.deployment.replicas_ready{env:$env.value,kube_namespace:fx-demo} by {kube_deployment}", display_type = "line", style = { palette = "green", line_type = "solid", line_width = "thick" } },
        ]
      }
    },
    {
      definition = {
        type        = "timeseries"
        title       = "Application containers · restarts"
        show_legend = true
        requests = [{
          q            = "max:kubernetes.containers.restarts{env:$env.value,kube_namespace:fx-demo} by {kube_deployment,pod_name}"
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
        content          = "### From aggregate impact to one order\nAll lists below follow the dashboard Time Picker and the dashboard **order_id** variable. Leave `order_id` as `*` to find affected orders, then select one concrete ID to focus every investigation list on the same order. **Unresolved dead letters** above is a current-state gauge, so widen the Time Picker when investigating an older unresolved item."
        background_color = "gray"
        font_size        = "14"
        text_align       = "left"
        show_tick        = false
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "Dead-lettered orders · selected window"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value service:dead-letter-service @order_id:$order_id.value @event_type:DEAD_LETTER"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@currency_pair" },
              { aggregation = "latest", facet = "@notional_usd" },
              { aggregation = "latest", facet = "@failure_category" },
              { aggregation = "latest", facet = "@retry_count" },
              { aggregation = "latest", facet = "@demo_scenario_id" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@currency_pair:latest", width = "auto" },
            { field = "@notional_usd:latest", width = "auto" },
            { field = "@failure_category:latest", width = "auto" },
            { field = "@retry_count:latest", width = "auto" },
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
        title       = "Rejected orders · not executed in selected window"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value @order_id:$order_id.value @event_type:(ORDER_REJECTED OR REJECTED)"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@currency_pair" },
              { aggregation = "latest", facet = "@event_type" },
              { aggregation = "count", facet = "count" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@currency_pair:latest", width = "auto" },
            { field = "@event_type:latest", width = "auto" },
            { field = "timeline", width = "auto" },
            { field = "duration", width = "auto" },
            { field = "max_severity", width = "auto" },
            { field = "count:count", width = "auto" },
          ]
        }]
      }
    },
    {
      definition = {
        type        = "list_stream"
        title       = "Retry / DLT activity · processing failures in selected window"
        title_size  = "16"
        title_align = "left"
        requests = [{
          response_format = "event_list"
          query = {
            data_source  = "logs_transaction_stream"
            query_string = "env:$env.value @order_id:$order_id.value @event_type:(RETRYING OR DEAD_LETTER)"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@currency_pair" },
              { aggregation = "latest", facet = "@event_type" },
              { aggregation = "latest", facet = "@demo_scenario_id" },
              { aggregation = "count", facet = "count" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@currency_pair:latest", width = "auto" },
            { field = "@event_type:latest", width = "auto" },
            { field = "@demo_scenario_id:latest", width = "auto" },
            { field = "timeline", width = "auto" },
            { field = "duration", width = "auto" },
            { field = "max_severity", width = "auto" },
            { field = "count:count", width = "auto" },
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
            query_string = "env:$env.value @order_id:$order_id.value service:(order-service OR pre-trade-control-service OR execution-adapter OR position-service OR risk-service OR confirmation-service OR settlement-service OR order-state-service OR dead-letter-service)"
            group_by     = [{ facet = "@order_id" }]
            compute = [
              { aggregation = "latest", facet = "@currency_pair" },
              { aggregation = "latest", facet = "@event_type" },
              { aggregation = "count", facet = "count" },
            ]
          }
          columns = [
            { field = "group_by", width = "auto" },
            { field = "@currency_pair:latest", width = "auto" },
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
    { x = 0, y = 4, width = 4, height = 2 },
    { x = 4, y = 4, width = 4, height = 2 },
    { x = 8, y = 4, width = 4, height = 2 },
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
        title            = "1 · Executive Summary · current business health"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_executive_summary_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "2 · Business Flow & Mix · throughput, notional, latency, and exceptions"
        layout_type      = "ordered"
        show_title       = true
        background_color = "transparent"
        widgets          = local.fixed_business_flow_widgets
      }
    },
    {
      definition = {
        type             = "group"
        title            = "3 · Orders Requiring Attention · open one order and inspect its lifecycle"
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

  demo_overview_group_layouts = [
    { x = 0, y = 2, width = 12, height = 8 },
    { x = 0, y = 10, width = 12, height = 14 },
    { x = 0, y = 24, width = 12, height = 10 },
    { x = 0, y = 34, width = 12, height = 8 },
    { x = 0, y = 42, width = 12, height = 8 },
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

resource "datadog_dashboard_json" "fx_demo" {
  dashboard = jsonencode({
    title       = "FX Order Flow · Datadog Data Streams Monitoring Demo"
    description = var.enable_workflows ? "Fictional FX order lifecycle. Detect business impact, drill down to one order, diagnose the responsible stream or service, and verify recovery in one Demo Overview; operational workflows remain on a separate Operator Controls tab." : "Fictional FX order lifecycle. Detect business impact, drill down to one order, diagnose the responsible stream or service, and verify recovery in one Demo Overview; the Operator Controls tab explains the CLI fallback."
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
    ]
    tabs    = local.dashboard_tabs
    widgets = local.dashboard_widgets
  })
}
