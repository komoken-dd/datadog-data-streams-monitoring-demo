output "dashboard_url" {
  value = datadog_dashboard_json.fx_demo.url
}

output "workflow_ids" {
  value = var.enable_workflows ? merge(
    { for name, workflow in datadog_workflow_automation.single_step : name => workflow.id },
    {
      reset_baseline = datadog_workflow_automation.reset_baseline[0].id
      redrive        = datadog_workflow_automation.redrive[0].id
    },
  ) : {}
}
