{{- define "kc.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: keycloak
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: sensor-platform
{{- end -}}

{{- define "kc.selector" -}}
app.kubernetes.io/name: keycloak
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "kc.jdbcUrl" -}}
{{- printf "jdbc:postgresql://%s:%v/%s" .Values.db.host .Values.db.port .Values.db.name -}}
{{- if .Values.db.sslmode -}}{{- printf "?sslmode=%s" .Values.db.sslmode -}}{{- end -}}
{{- end -}}
