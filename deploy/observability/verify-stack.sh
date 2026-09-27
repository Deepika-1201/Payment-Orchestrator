#!/usr/bin/env bash
# Verifies a running local stack (docker compose --profile observability up, ADR-027): the application is ready,
# Prometheus scrapes it and evaluates every rule without errors, Grafana has the dashboard, and traces reach Tempo.
# Needs curl and jq. Usage: deploy/observability/verify-stack.sh
set -euo pipefail

APP=${APP:-http://localhost:8080}
PROMETHEUS=${PROMETHEUS:-http://localhost:9090}
GRAFANA=${GRAFANA:-http://localhost:3000}
TEMPO=${TEMPO:-http://localhost:3200}

retry() {
  local what=$1
  shift
  for _ in $(seq 1 90); do
    if "$@" > /dev/null 2>&1; then
      echo "ok: $what"
      return 0
    fi
    sleep 2
  done
  echo "FAILED: $what" >&2
  return 1
}

prometheus_query() {
  curl -fsS -G "$PROMETHEUS/api/v1/query" --data-urlencode "query=$1" | jq -e '.data.result | length > 0'
}

rules_healthy() {
  curl -fsS "$PROMETHEUS/api/v1/rules" | jq -e '
    ([.data.groups[].name] | contains(["payment-gateway-slo", "payment-gateway-alerts"]))
    and ([.data.groups[].rules[]] | length > 30)
    and ([.data.groups[].rules[] | select(.health != "ok")] | length == 0)'
}

traces_found() {
  curl -fsS -G "$TEMPO/api/search" --data-urlencode 'q={resource.service.name="payment-gateway"}' \
    | jq -e '.traces | length > 0'
}

retry "application is ready" curl -fsS "$APP/actuator/health/readiness"
for _ in 1 2 3 4 5; do
  curl -s -o /dev/null "$APP/v1/payments/pay_does_not_exist" || true
done
retry "Prometheus scrapes the application" prometheus_query 'up{job="payment-gateway"} == 1'
retry "application series reach Prometheus" prometheus_query 'pg_webhook_deliveries_total'
retry "every recording and alerting rule evaluates without errors" rules_healthy
retry "Grafana provisioned the dashboard" curl -fsS "$GRAFANA/api/dashboards/uid/payment-gateway-overview"
retry "Tempo is ready" curl -fsS "$TEMPO/ready"
retry "application traces reach Tempo through the collector" traces_found
echo "observability stack verified"
