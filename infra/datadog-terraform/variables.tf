variable "datadog_site" {
  description = "Datadog site, for example datadoghq.com or ap1.datadoghq.com."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9.-]+$", var.datadog_site))
    error_message = "datadog_site must be a hostname without a scheme or trailing slash."
  }
}

variable "datadog_ui_base_url" {
  description = "Organization-specific Datadog UI base URL without a trailing slash."
  type        = string

  validation {
    condition     = can(regex("^https://[^/]+$", var.datadog_ui_base_url))
    error_message = "datadog_ui_base_url must be an HTTPS origin without a trailing slash."
  }
}

variable "environment" {
  description = "Unified service tagging environment used by this dashboard and its target workloads. Use a separate Terraform state and dashboard for each environment."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9_.-]*$", var.environment))
    error_message = "environment must be a non-empty lowercase unified service tag value."
  }
}

variable "enable_workflows" {
  description = "Create Workflow Automation controls backed by Private HTTP Connections. Leave false to use scripts/demo-control instead."
  type        = bool
  default     = false
}

variable "generator_connection_id" {
  description = "Private HTTP connection ID whose base URL is http://order-generator.fx-demo.svc.cluster.local:8080."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = !var.enable_workflows || (
      can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", coalesce(var.generator_connection_id, ""))) &&
      coalesce(var.generator_connection_id, "") != "00000000-0000-0000-0000-000000000000"
    )
    error_message = "generator_connection_id must be a non-zero Private HTTP Connection UUID when enable_workflows is true."
  }
}

variable "execution_connection_id" {
  description = "Private HTTP connection ID whose base URL is http://execution-adapter.fx-demo.svc.cluster.local:8080."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = !var.enable_workflows || (
      can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", coalesce(var.execution_connection_id, ""))) &&
      coalesce(var.execution_connection_id, "") != "00000000-0000-0000-0000-000000000000"
    )
    error_message = "execution_connection_id must be a non-zero Private HTTP Connection UUID when enable_workflows is true."
  }
}

variable "dead_letter_connection_id" {
  description = "Private HTTP connection ID whose base URL is http://dead-letter-service.fx-demo.svc.cluster.local:8080."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = !var.enable_workflows || (
      can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", coalesce(var.dead_letter_connection_id, ""))) &&
      coalesce(var.dead_letter_connection_id, "") != "00000000-0000-0000-0000-000000000000"
    )
    error_message = "dead_letter_connection_id must be a non-zero Private HTTP Connection UUID when enable_workflows is true."
  }
}
