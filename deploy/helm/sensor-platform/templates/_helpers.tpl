{{- define "sp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sp.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Labels common to every object. */}}
{{- define "sp.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "sp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: sensor-platform
{{- end -}}

{{/* Selector labels of one component. Call with (dict "root" . "component" "app"). */}}
{{- define "sp.selector" -}}
app.kubernetes.io/name: {{ include "sp.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Image reference. Call with (dict "root" . "image" .Values.image.app). */}}
{{- define "sp.image" -}}
{{- $reg := .root.Values.image.registry -}}
{{- $tag := default .root.Chart.AppVersion .image.tag -}}
{{- if $reg -}}
{{- printf "%s/%s:%s" (trimSuffix "/" $reg) .image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end -}}
{{- end -}}

{{/* Names of the Kubernetes Secrets this chart owns. */}}
{{- define "sp.secretName" -}}
{{- printf "%s-%s-secrets" (include "sp.fullname" .root) .component -}}
{{- end -}}

{{/* JDBC URL; the TLS mode is part of the URL, as the PostgreSQL driver expects. */}}
{{- define "sp.jdbcUrl" -}}
{{- $db := .Values.config.db -}}
{{- printf "jdbc:postgresql://%s:%v/%s" $db.host $db.port $db.name -}}
{{- if $db.sslmode -}}{{- printf "?sslmode=%s" $db.sslmode -}}{{- end -}}
{{- end -}}

{{/* Hardened container security context shared by every container. */}}
{{- define "sp.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{/* NetworkPolicy egress rule. Call with (dict "peers" list "ports" list); renders nothing when no peers are set. */}}
{{- define "sp.egressRule" -}}
{{- if .peers }}
- to:
    {{- toYaml .peers | nindent 4 }}
  ports:
    {{- range .ports }}
    - protocol: TCP
      port: {{ . }}
    {{- end }}
{{- end }}
{{- end -}}
