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
    condition     = datadog_apm_retention_filter.fx_demo_orders.filter.query == "env:local AND @order_id:*"
    error_message = "The APM retention filter must use the selected environment."
  }
}

run "workflows_enabled" {
  command = plan

  variables {
    datadog_site              = "datadoghq.com"
    datadog_ui_base_url       = "https://app.datadoghq.com"
    environment               = "demo"
    enable_workflows          = true
    generator_connection_id   = "11111111-1111-4111-8111-111111111111"
    execution_connection_id   = "22222222-2222-4222-8222-222222222222"
    dead_letter_connection_id = "33333333-3333-4333-8333-333333333333"
  }

  assert {
    condition = (
      length(datadog_workflow_automation.single_step) == 4 &&
      length(datadog_workflow_automation.reset_baseline) == 1 &&
      length(datadog_workflow_automation.redrive) == 1
    )
    error_message = "All six Workflow resources must be planned when workflows are enabled."
  }

  assert {
    condition     = local.demo_control_widget_count == 7
    error_message = "The Operator Controls tab must show its introduction and six buttons when workflows are enabled."
  }
}
