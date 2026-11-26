locals {
  workflow_tags = ["env:${var.environment}", "service:brokerage-demo", "purpose:learning-demo"]

  simulator_base_url    = "http://order-flow-simulator.brokerage-demo.svc.cluster.local:8080"
  exchange_base_url     = "http://exchange-gateway-simulator.brokerage-demo.svc.cluster.local:8080"
  buying_power_base_url = "http://buying-power-service.brokerage-demo.svc.cluster.local:8080"
  push_gateway_base_url = "http://push-gateway-simulator.brokerage-demo.svc.cluster.local:8080"
  exception_base_url    = "http://exception-management-service.brokerage-demo.svc.cluster.local:8080"

  single_step_workflows = {
    stop_generation = {
      name             = "証券デモ - 注文フロー停止"
      description      = "新規の模擬注文を停止します。受付済みの注文は引き続き約定、買付余力への反映、通知まで処理されます。"
      handle           = "brokerage-demo-stop-order-flow"
      connection_id    = var.simulator_connection_id
      connection_label = "SIMULATOR_PRIVATE_HTTP"
      url              = "${local.simulator_base_url}/internal/demo/stop"
    }
    opening_rush = {
      name             = "証券デモ - 寄付き集中"
      description      = "寄付き前の注文を毎秒 25 件で 60 秒間ため、板寄せで一斉に約定させた後、ベースラインに戻します。"
      handle           = "brokerage-demo-opening-rush"
      connection_id    = var.simulator_connection_id
      connection_label = "SIMULATOR_PRIVATE_HTTP"
      url              = "${local.simulator_base_url}/internal/demo/opening-rush"
    }
    sell_off = {
      name             = "証券デモ - 材料による売り殺到 · D105"
      description      = "架空銘柄 D105 に 120 秒間売り注文を殺到させ、株価をストップ安まで下落させて未約定の売り注文を積み上げた後、ベースラインに戻します。"
      handle           = "brokerage-demo-sell-off"
      connection_id    = var.simulator_connection_id
      connection_label = "SIMULATOR_PRIVATE_HTTP"
      url              = "${local.simulator_base_url}/internal/demo/sell-off"
    }
    buying_power_delay = {
      name             = "証券デモ - 買付余力の反映遅延"
      description      = "buying-power-service の約定処理を遅らせます。売却代金の反映が遅れ、乗り換え取引が買付余力不足で拒否されます。"
      handle           = "brokerage-demo-buying-power-delay"
      connection_id    = var.buying_power_connection_id
      connection_label = "BUYING_POWER_PRIVATE_HTTP"
      url              = "${local.buying_power_base_url}/internal/demo/delay"
    }
    push_outage = {
      name             = "証券デモ - プッシュ通知事業者の障害"
      description      = "プッシュゲートウェイが 503 を返すようにし、お客様向け通知を再試行トピック経由で DLT に送ります。"
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

  name        = "証券デモ - ベースラインに戻す"
  description = "通常の注文フロー、基準価格でのザラバ（継続売買）、通常の買付余力反映、正常なプッシュ通知事業者の状態に戻します。"
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

  name        = "証券デモ - 未達通知の再送 (Redrive)"
  description = "未解決の通知デッドレターを 5 秒の再試行トピックへ再投入します。既定では 1 回最大 100 件・送信予算 5 秒のバッチです。結果の remainingCount がゼロになるまで繰り返し、配信完了は配信イベントで確認してください。"
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
