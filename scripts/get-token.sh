#!/usr/bin/env bash
# Print an access token for one of the demo identities (curl only, no jq).
# Usage: scripts/get-token.sh <identity>      identity: platform-admin | alpha-admin | beta-admin | alpha-ingest | beta-ingest
# Reads secrets from .env (or the environment). Example:
#   TOKEN=$(scripts/get-token.sh platform-admin)
#   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/tenants
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; . ./.env; set +a; fi
KC="${KEYCLOAK_URL:-http://localhost:${KEYCLOAK_HOST_PORT:-8180}}"
URL="$KC/realms/sensor-platform/protocol/openid-connect/token"
id="${1:-}"
case "$id" in
  platform-admin|alpha-admin|beta-admin)
    resp=$(curl -sS -X POST "$URL" -d grant_type=password -d client_id=platform-cli \
      -d "username=$id" --data-urlencode "password=${DEMO_USER_PASSWORD:?DEMO_USER_PASSWORD not set}") ;;
  alpha-ingest)
    resp=$(curl -sS -X POST "$URL" -d grant_type=client_credentials -d client_id=tenant-alpha-ingest \
      --data-urlencode "client_secret=${ALPHA_INGEST_CLIENT_SECRET:?not set}") ;;
  beta-ingest)
    resp=$(curl -sS -X POST "$URL" -d grant_type=client_credentials -d client_id=tenant-beta-ingest \
      --data-urlencode "client_secret=${BETA_INGEST_CLIENT_SECRET:?not set}") ;;
  *) echo "usage: $0 platform-admin|alpha-admin|beta-admin|alpha-ingest|beta-ingest" >&2; exit 2 ;;
esac
tok=$(printf '%s' "$resp" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
if [ -z "$tok" ]; then echo "token request failed: $resp" >&2; exit 1; fi
printf '%s\n' "$tok"
