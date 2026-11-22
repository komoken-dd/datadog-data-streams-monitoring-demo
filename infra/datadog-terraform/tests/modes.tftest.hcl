mock_provider "datadog" {}

run "workflows_disabled" {
  command = plan

  variables {
    datadog_site        = "datadoghq.com"
    datadog_ui_base_url = "https://app.datadoghq.com"
    environment         = "local"
    enable_workflows    = false
  }

  assert {
    condition = (
      length(datadog_workflow_automation.single_step) == 0 &&
      length(datadog_workflow_automation.reset_baseline) == 0 &&
      length(datadog_workflow_automation.redrive) == 0
    )
    error_message = "Workflow resources must not be planned when workflows are disabled."
  }

  assert {
    condition     = local.demo_control_widget_count == 1
    error_message = "The Operator Controls tab must show only the CLI guidance when workflows are disabled."
  }

  assert {
    condition     = datadog_apm_retention_filter.brokerage_demo_orders.filter.query == "env:local AND @order_id:*"
    error_message = "The APM retention filter must use the selected environment."
  }
}

run "workflows_enabled" {
  command = plan

  variables {
    datadog_site               = "datadoghq.com"
    datadog_ui_base_url        = "https://app.datadoghq.com"
    environment                = "demo"
    enable_workflows           = true
    simulator_connection_id    = "11111111-1111-4111-8111-111111111111"
    exchange_connection_id     = "22222222-2222-4222-8222-222222222222"
    buying_power_connection_id = "33333333-3333-4333-8333-333333333333"
    push_gateway_connection_id = "44444444-4444-4444-8444-444444444444"
    exception_connection_id    = "55555555-5555-4555-8555-555555555555"
  }

  assert {
    condition = (
      length(datadog_workflow_automation.single_step) == 5 &&
      length(datadog_workflow_automation.reset_baseline) == 1 &&
      length(datadog_workflow_automation.redrive) == 1
    )
    error_message = "All seven Workflow resources must be planned when workflows are enabled."
  }

  assert {
    condition     = local.demo_control_widget_count == 8
    error_message = "The Operator Controls tab must show its introduction and seven buttons when workflows are enabled."
  }

  assert {
    condition     = length(local.demo_control_widget_layouts) == local.demo_control_widget_count
    error_message = "Every Operator Controls widget must have a layout."
  }

  assert {
    condition     = datadog_apm_retention_filter.brokerage_demo_orders.filter.query == "env:demo AND @order_id:*"
    error_message = "The APM retention filter must use the selected environment."
  }
}

run "workflows_enabled_requires_connections" {
  command = plan

  variables {
    datadog_site            = "datadoghq.com"
    datadog_ui_base_url     = "https://app.datadoghq.com"
    environment             = "demo"
    enable_workflows        = true
    simulator_connection_id = "11111111-1111-4111-8111-111111111111"
  }

  expect_failures = [
    var.exchange_connection_id,
    var.buying_power_connection_id,
    var.push_gateway_connection_id,
    var.exception_connection_id,
  ]
}
