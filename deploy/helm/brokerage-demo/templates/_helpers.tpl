{{- define "brokerage-demo.labels" -}}
app.kubernetes.io/part-of: brokerage-demo
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

