locals {
  workflow_tags = ["env:${var.environment}", "service:fx-demo", "purpose:learning-demo"]

  single_step_workflows = {
    stop_generation = {
      name             = "FX Demo - Stop Order Generation"
      description      = "Stop new order generation while allowing orders already accepted into the pipeline to finish processing."
      handle           = "fx-demo-stop-order-generation"
      connection_id    = var.generator_connection_id
      connection_label = "GENERATOR_PRIVATE_HTTP"
      url              = "http://order-generator.fx-demo.svc.cluster.local:8080/internal/demo/stop"
    }
    generate_surge = {
      name             = "FX Demo - Generate Order Surge"
      description      = "Increase order generation from 2 to 20 orders/s for 90 seconds, then return to baseline automatically."
      handle           = "fx-demo-generate-order-surge"
      connection_id    = var.generator_connection_id
      connection_label = "GENERATOR_PRIVATE_HTTP"
      url              = "http://order-generator.fx-demo.svc.cluster.local:8080/internal/demo/surge"
    }
    inject_delay = {
      name             = "FX Demo - Inject Execution Delay"
      description      = "Slow the execution adapter so Kafka lag, pending notional, and end-to-end duration rise."
      handle           = "fx-demo-inject-execution-delay"
      connection_id    = var.execution_connection_id
      connection_label = "EXECUTION_PRIVATE_HTTP"
      url              = "http://execution-adapter.fx-demo.svc.cluster.local:8080/internal/demo/delay"
    }
    inject_failure = {
      name             = "FX Demo - Inject Processing Failure"
      description      = "Mark exactly the next ten orders with a unique scenario ID so they traverse retry topics into the DLT."
      handle           = "fx-demo-inject-processing-failure"
      connection_id    = var.generator_connection_id
      connection_label = "GENERATOR_PRIVATE_HTTP"
      url              = "http://order-generator.fx-demo.svc.cluster.local:8080/internal/demo/processing-failure"
    }
  }
}

resource "datadog_workflow_automation" "single_step" {
  for_each = var.enable_workflows ? local.single_step_workflows : {}

  name        = each.value.name
  description = each.value.description
  tags        = local.workflow_tags
  published   = true

  spec_json = jsonencode({
    handle = each.value.handle
    connectionEnvs = [{
      env = "default"
      connections = [{
        connectionId = each.value.connection_id
        label        = each.value.connection_label
      }]
    }]
    triggers = [
      {
        startStepNames = ["Call_demo_control"]
        apiTrigger     = {}
      },
      {
        startStepNames   = ["Call_demo_control"]
        dashboardTrigger = {}
      },
    ]
    steps = [{
      name            = "Call_demo_control"
      actionId        = "com.datadoghq.http.request"
      connectionLabel = each.value.connection_label
      parameters = [
        { name = "verb", value = "POST" },
        { name = "url", value = each.value.url },
      ]
      display = { bounds = { x = 0, y = 0 } }
    }]
  })
}

resource "datadog_workflow_automation" "reset_baseline" {
  count = var.enable_workflows ? 1 : 0

  name        = "FX Demo - Reset to Baseline"
  description = "Restore normal order intake and execution time so queued orders can complete."
  tags        = local.workflow_tags
  published   = true

  spec_json = jsonencode({
    handle = "fx-demo-reset-to-baseline"
    connectionEnvs = [{
      env = "default"
      connections = [
        { connectionId = var.generator_connection_id, label = "GENERATOR_PRIVATE_HTTP" },
        { connectionId = var.execution_connection_id, label = "EXECUTION_PRIVATE_HTTP" },
      ]
    }]
    triggers = [
      {
        startStepNames = ["Restore_generation"]
        apiTrigger     = {}
      },
      {
        startStepNames   = ["Restore_generation"]
        dashboardTrigger = {}
      },
    ]
    steps = [
      {
        name            = "Restore_generation"
        actionId        = "com.datadoghq.http.request"
        connectionLabel = "GENERATOR_PRIVATE_HTTP"
        parameters = [
          { name = "verb", value = "POST" },
          { name = "url", value = "http://order-generator.fx-demo.svc.cluster.local:8080/internal/demo/baseline" },
        ]
        outboundEdges = [{ branchName = "main", nextStepName = "Restore_execution" }]
        display       = { bounds = { x = 0, y = 0 } }
      },
      {
        name            = "Restore_execution"
        actionId        = "com.datadoghq.http.request"
        connectionLabel = "EXECUTION_PRIVATE_HTTP"
        parameters = [
          { name = "verb", value = "POST" },
          { name = "url", value = "http://execution-adapter.fx-demo.svc.cluster.local:8080/internal/demo/recover" },
        ]
        display = { bounds = { x = 0, y = 180 } }
      },
    ]
  })
}

resource "datadog_workflow_automation" "redrive" {
  count = var.enable_workflows ? 1 : 0

  name        = "FX Demo - Redrive Dead Letters"
  description = "Redrive every currently unresolved DLT record so affected orders can continue toward execution."
  tags        = local.workflow_tags
  published   = true

  spec_json = jsonencode({
    handle = "fx-demo-redrive-dead-letters"
    connectionEnvs = [{
      env = "default"
      connections = [{
        connectionId = var.dead_letter_connection_id
        label        = "DEAD_LETTER_PRIVATE_HTTP"
      }]
    }]
    triggers = [
      {
        startStepNames = ["Redrive_all_unresolved"]
        apiTrigger     = {}
      },
      {
        startStepNames   = ["Redrive_all_unresolved"]
        dashboardTrigger = {}
      },
    ]
    steps = [{
      name            = "Redrive_all_unresolved"
      actionId        = "com.datadoghq.http.request"
      connectionLabel = "DEAD_LETTER_PRIVATE_HTTP"
      parameters = [
        { name = "verb", value = "POST" },
        { name = "url", value = "http://dead-letter-service.fx-demo.svc.cluster.local:8080/internal/demo/redrive" },
      ]
      display = { bounds = { x = 0, y = 0 } }
    }]
  })
}
