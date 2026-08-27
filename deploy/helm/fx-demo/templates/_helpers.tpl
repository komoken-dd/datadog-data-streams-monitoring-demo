{{- define "fx-demo.labels" -}}
app.kubernetes.io/part-of: fx-demo
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

