terraform {
  required_version = ">= 1.9.0"

  backend "local" {}

  required_providers {
    datadog = {
      source  = "DataDog/datadog"
      version = "4.16.0"
    }
  }
}

provider "datadog" {
  api_url = "https://api.${var.datadog_site}/"
}
