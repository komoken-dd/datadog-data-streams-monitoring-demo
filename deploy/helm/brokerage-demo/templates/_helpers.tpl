{{- define "brokerage-demo.labels" -}}
app.kubernetes.io/part-of: brokerage-demo
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}


{{- define "brokerage-demo.databaseSchema" -}}
{{- $schemas := dict "order-service" "order_service" "buying-power-service" "buying_power_service" "exchange-gateway-simulator" "exchange_gateway" "holdings-service" "holdings_service" "trade-report-service" "trade_report_service" "order-state-service" "order_state_service" "exception-management-service" "exception_management_service" -}}
{{- required "No database schema defined for application" (get $schemas .name) -}}
{{- end }}

{{- define "brokerage-demo.applicationImage" -}}
{{- $reference := get .root.Values.image.references .application.name -}}
{{- if $reference -}}
{{- $reference -}}
{{- else -}}
{{- printf "%s/%s:%s" .root.Values.image.repositoryPrefix .application.name .root.Values.image.tag -}}
{{- end -}}
{{- end }}
