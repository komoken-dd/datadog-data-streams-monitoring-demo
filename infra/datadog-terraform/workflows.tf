locals {
  workflow_tags = ["env:${var.environment}", "service:brokerage-demo", "purpose:learning-demo"]

  simulator_base_url    = "http://order-flow-simulator.brokerage-demo.svc.cluster.local:8080"
  exchange_base_url     = "http://exchange-gateway-simulator.brokerage-demo.svc.cluster.local:8080"
  buying_power_base_url = "http://buying-power-service.brokerage-demo.svc.cluster.local:8080"
  push_gateway_base_url = "http://push-gateway-simulator.brokerage-demo.svc.cluster.local:8080"
  exception_base_url    = "http://exception-management-service.brokerage-demo.svc.cluster.local:8080"

  single_step_workflows = {
    stop_generation = {
      name             = "Brokerage Demo - Stop Order Flow"
      description      = "Stop new simulated customer orders while orders already accepted continue through execution, buying-power reflection, and notification."
      handle           = "brokerage-demo-stop-order-flow"
      connection_id    = var.simulator_connection_id
      connection_label = "SIMULATOR_PRIVATE_HTTP"
      url              = "${local.simulator_base_url}/internal/demo/stop"
    }
    opening_rush = {
      name             = "Brokerage Demo - Opening Rush"
      description      = "Accumulate pre-open orders at 25 orders/s for 60 seconds, then release them in one opening-auction burst before returning to baseline."
      handle           = "brokerage-demo-opening-rush"
      connection_id    = var.simulator_connection_id
      connection_label = "SIMULATOR_PRIVATE_HTTP"
      url              = "${local.simulator_base_url}/internal/demo/opening-rush"
    }
    sell_off = {
      name             = "Brokerage Demo - News-Driven Sell-Off on D105"
      description      = "Flood fictional symbol D105 with sell orders for 120 seconds so its price falls to limit-down and unfilled sell orders pile up, then return to baseline."
      handle           = "brokerage-demo-sell-off"
      connection_id    = var.simulator_connection_id
      connection_label = "SIMULATOR_PRIVATE_HTTP"
      url              = "${local.simulator_base_url}/internal/demo/sell-off"
    }
    buying_power_delay = {
      name             = "Brokerage Demo - Delay Buying-Power Reflection"
      description      = "Slow buying-power-service execution processing so sale proceeds are reflected late and switch trades are rejected for insufficient buying power."
      handle           = "brokerage-demo-buying-power-delay"
      connection_id    = var.buying_power_connection_id
      connection_label = "BUYING_POWER_PRIVATE_HTTP"
      url              = "${local.buying_power_base_url}/internal/demo/delay"
    }
    push_outage = {
      name             = "Brokerage Demo - Push Provider Outage"
      description      = "Make the push gateway return 503 so customer notifications move through the retry topics into the DLT."
      handle           = "brokerage-demo-push-outage"
      connection_id    = var.push_gateway_connection_id
      connection_label = "PUSH_GATEWAY_PRIVATE_HTTP"
      url              = "${local.push_gateway_base_url}/internal/demo/outage"
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

  name        = "Brokerage Demo - Reset to Baseline"
  description = "Restore baseline order flow, a continuous exchange session at base prices, normal buying-power reflection, and a healthy push provider."
  tags        = local.workflow_tags
  published   = true

  spec_json = jsonencode({
    handle = "brokerage-demo-reset-to-baseline"
    connectionEnvs = [{
      env = "default"
      connections = [
        { connectionId = var.simulator_connection_id, label = "SIMULATOR_PRIVATE_HTTP" },
        { connectionId = var.exchange_connection_id, label = "EXCHANGE_PRIVATE_HTTP" },
        { connectionId = var.buying_power_connection_id, label = "BUYING_POWER_PRIVATE_HTTP" },
        { connectionId = var.push_gateway_connection_id, label = "PUSH_GATEWAY_PRIVATE_HTTP" },
      ]
    }]
    triggers = [
      {
        startStepNames = ["Restore_order_flow"]
        apiTrigger     = {}
      },
      {
        startStepNames   = ["Restore_order_flow"]
        dashboardTrigger = {}
      },
    ]
    steps = [
      {
        name            = "Restore_order_flow"
        actionId        = "com.datadoghq.http.request"
        connectionLabel = "SIMULATOR_PRIVATE_HTTP"
        parameters = [
          { name = "verb", value = "POST" },
          { name = "url", value = "${local.simulator_base_url}/internal/demo/baseline" },
        ]
        outboundEdges = [{ branchName = "main", nextStepName = "Reset_exchange" }]
        display       = { bounds = { x = 0, y = 0 } }
      },
      {
        name            = "Reset_exchange"
        actionId        = "com.datadoghq.http.request"
        connectionLabel = "EXCHANGE_PRIVATE_HTTP"
        parameters = [
          { name = "verb", value = "POST" },
          { name = "url", value = "${local.exchange_base_url}/internal/demo/reset" },
        ]
        outboundEdges = [{ branchName = "main", nextStepName = "Recover_buying_power" }]
        display       = { bounds = { x = 0, y = 180 } }
      },
      {
        name            = "Recover_buying_power"
        actionId        = "com.datadoghq.http.request"
        connectionLabel = "BUYING_POWER_PRIVATE_HTTP"
        parameters = [
          { name = "verb", value = "POST" },
          { name = "url", value = "${local.buying_power_base_url}/internal/demo/recover" },
        ]
        outboundEdges = [{ branchName = "main", nextStepName = "Recover_push_gateway" }]
        display       = { bounds = { x = 0, y = 360 } }
      },
      {
        name            = "Recover_push_gateway"
        actionId        = "com.datadoghq.http.request"
        connectionLabel = "PUSH_GATEWAY_PRIVATE_HTTP"
        parameters = [
          { name = "verb", value = "POST" },
          { name = "url", value = "${local.push_gateway_base_url}/internal/demo/recover" },
        ]
        display = { bounds = { x = 0, y = 540 } }
      },
    ]
  })
}

resource "datadog_workflow_automation" "redrive" {
  count = var.enable_workflows ? 1 : 0

  name        = "Brokerage Demo - Redrive Undelivered Notifications"
  description = "Redrive every unresolved notification dead letter to the 5s retry topic so customers finally receive their execution or expiry notification."
  tags        = local.workflow_tags
  published   = true

  spec_json = jsonencode({
    handle = "brokerage-demo-redrive-notifications"
    connectionEnvs = [{
      env = "default"
      connections = [{
        connectionId = var.exception_connection_id
        label        = "EXCEPTION_PRIVATE_HTTP"
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
      connectionLabel = "EXCEPTION_PRIVATE_HTTP"
      parameters = [
        { name = "verb", value = "POST" },
        { name = "url", value = "${local.exception_base_url}/internal/demo/redrive" },
      ]
      display = { bounds = { x = 0, y = 0 } }
    }]
  })
}
