#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
DOWN_SCRIPT="$SCRIPT_DIR/down.sh"

usage() {
  cat <<'EOF'
Usage: verify.sh [--cleanup]

Verify the java-learn-lab dev stack.

By default the stack is left running for interactive debugging. Use --cleanup
to stop it afterwards and prove that the compose project is empty while the
named volumes are preserved.
EOF
}

CLEANUP=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cleanup)
      CLEANUP=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run '$SCRIPT_DIR/up.sh --profile dev' first." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"
GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-labadmin}"
LAB_TOKEN="${LAB_TOKEN:-lab-token}"

compose() {
  docker compose \
    --project-name java-learn-lab \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$DOCKER_DIR/compose.base.yml" \
    -f "$DOCKER_DIR/compose.dev.yml" \
    "$@"
}

PASSED=0
FAILED=0
WARNED=0

pass() {
  printf 'PASS  %s\n' "$1"
  PASSED=$((PASSED + 1))
}

fail() {
  printf 'FAIL  %s' "$1"
  if [[ -n "${2:-}" ]]; then
    printf ' (%s)' "$2"
  fi
  printf '\n'
  FAILED=$((FAILED + 1))
}

warn() {
  printf 'WARN  %s\n' "$1"
  WARNED=$((WARNED + 1))
}

check_metric() {
  local label="$1" url="$2" needle="$3" body="" attempt
  for attempt in $(seq 1 15); do
    if body="$(docker exec jl-nginx wget -qO- -T 5 "$url" 2>/dev/null)" && grep -qF "$needle" <<<"$body"; then
      pass "$label"
      return 0
    fi
    if (( attempt < 15 )); then
      sleep 1
    fi
  done
  fail "$label" "missing '$needle' from $url"
}

check_http_ok() {
  local label="$1" url="$2" attempt
  for attempt in $(seq 1 20); do
    if docker exec jl-nginx wget -q -O /dev/null -T 5 "$url" 2>/dev/null; then
      pass "$label"
      return 0
    fi
    if (( attempt < 20 )); then
      sleep 1
    fi
  done
  fail "$label" "GET $url did not return 2xx"
}

# Query the Prometheus HTTP API from inside jl-net. The caller passes a
# percent-encoded query expression so no host-side URL encoding is required.
prometheus_query() {
  local encoded_query="$1"
  docker exec jl-nginx wget -qO- -T 5 \
    "http://prometheus:9090/api/v1/query?query=${encoded_query}" 2>/dev/null || true
}

dashboard_http_code() {
  local uid="$1"
  curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -u "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
    "http://127.0.0.1:3000/api/dashboards/uid/${uid}" 2>/dev/null || true
}

echo "Verifying java-learn-lab..."

# 1. Containers: dynamic list from the rendered compose model.
services=()
while IFS= read -r service; do
  if [[ -n "$service" ]]; then
    services+=("$service")
  fi
done < <(compose config --services)

if (( ${#services[@]} == 0 )); then
  fail "docker compose config --services lists services" "empty service list"
fi

for service in "${services[@]}"; do
  container="jl-$service"
  state="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
  if [[ "$state" == "running" ]]; then
    pass "service $container is running"
  else
    fail "service $container is running" "state=${state:-missing}"
  fi
done

# 2. Nginx health endpoint.
health_body="$(curl -fsS --max-time 5 http://127.0.0.1:8080/health 2>/dev/null || true)"
health_body="$(printf '%s' "$health_body" | tr -d '\r\n')"
if [[ "$health_body" == "ok" ]]; then
  pass "nginx /health returns ok"
else
  fail "nginx /health returns ok" "got '${health_body}'"
fi

# 2b. Application tier through Nginx.
# Remember when this run's hello traffic starts (one second of slack for clock
# granularity). The Tempo check below is bounded to this window so a trace kept
# from an earlier run cannot hide a broken live OTel export.
hello_window_start="$(( $(date +%s) - 1 ))"
hello_response="$(curl -sS -w $'\n%{http_code}' --max-time 5 \
  http://127.0.0.1:8080/api/hello 2>/dev/null || true)"
hello_code="${hello_response##*$'\n'}"
hello_body="${hello_response%$'\n'*}"
if [[ "$hello_code" == "200" ]] \
  && grep -Eq '"message"[[:space:]]*:[[:space:]]*"hello"' <<<"$hello_body" \
  && grep -Eq '"instance"[[:space:]]*:[[:space:]]*"app-' <<<"$hello_body"; then
  pass "nginx /api/hello returns 200 JSON with message and instance"
else
  fail "nginx /api/hello returns 200 JSON with message and instance" \
    "HTTP ${hello_code:-000} body: ${hello_body:0:120}"
fi

# Nine sequential calls give round-robin three turns per instance.
hello_instances=""
hello_calls_ok=true
for _ in $(seq 1 9); do
  call_body="$(curl -fsS --max-time 5 http://127.0.0.1:8080/api/hello 2>/dev/null || true)"
  call_instance="$(sed -n 's/.*"instance"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$call_body")"
  if [[ -z "$call_instance" ]]; then
    hello_calls_ok=false
  else
    hello_instances="${hello_instances}${call_instance}"$'\n'
  fi
done
unique_instances="$(printf '%s' "$hello_instances" | sed '/^[[:space:]]*$/d' | sort -u)"
# printf '%s\n' keeps the last line for wc -l; command substitution strips the
# trailing newline from sort, which would undercount by one.
unique_count="$(printf '%s\n' "$unique_instances" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
if $hello_calls_ok && [[ "$unique_count" == "3" ]] && ! grep -qv '^app-' <<<"$unique_instances"; then
  pass "nine /api/hello calls hit all three app instances (round-robin)"
else
  fail "nine /api/hello calls hit all three app instances (round-robin)" \
    "unique instances: $(printf '%s' "$unique_instances" | tr '\n' ' ')"
fi

# 2c. Catalog API through Nginx (Task 12: Flyway schema + JPA adapter + query use case).
# Three calls also prove every app instance can reach MySQL, not just the first.
product_body=""
product_code=""
product_calls_ok=true
for _ in $(seq 1 3); do
  product_response="$(curl -sS -w $'\n%{http_code}' --max-time 5 \
    http://127.0.0.1:8080/api/products/1 2>/dev/null || true)"
  product_code="${product_response##*$'\n'}"
  product_body="${product_response%$'\n'*}"
  if [[ "$product_code" != "200" ]]; then
    product_calls_ok=false
    break
  fi
done
if $product_calls_ok \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$product_body" \
  && grep -Eq '"id"[[:space:]]*:[[:space:]]*1([^0-9]|$)' <<<"$product_body" \
  && grep -Eq '"name"[[:space:]]*:[[:space:]]*"[^"]+"' <<<"$product_body" \
  && grep -Eq '"price"[[:space:]]*:[[:space:]]*[0-9]+(\.[0-9]+)?' <<<"$product_body"; then
  pass "nginx /api/products/1 returns 200 with code=0, id, name and price (3 instances)"
else
  fail "nginx /api/products/1 returns 200 with code=0, id, name and price (3 instances)" \
    "HTTP ${product_code:-000} body: ${product_body:0:160}"
fi

missing_product_response="$(curl -sS -w $'\n%{http_code}' --max-time 5 \
  http://127.0.0.1:8080/api/products/999999 2>/dev/null || true)"
missing_product_code="${missing_product_response##*$'\n'}"
missing_product_body="${missing_product_response%$'\n'*}"
if [[ "$missing_product_code" == "404" ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"40401"' <<<"$missing_product_body"; then
  pass "nginx /api/products/999999 returns 404 with code=40401"
else
  fail "nginx /api/products/999999 returns 404 with code=40401" \
    "HTTP ${missing_product_code:-000} body: ${missing_product_body:0:160}"
fi

# 3. RabbitMQ management API.
rabbit_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
  -u "${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}" \
  http://127.0.0.1:15672/api/overview 2>/dev/null || true)"
if [[ "$rabbit_code" == "200" ]]; then
  pass "rabbitmq management API returns 200"
else
  fail "rabbitmq management API returns 200" "got HTTP ${rabbit_code:-000}"
fi

# 4. MySQL liveness from inside the container.
if docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" jl-mysql \
  mysqladmin ping -h 127.0.0.1 -uroot --silent >/dev/null 2>&1; then
  pass "mysqladmin ping succeeds"
else
  fail "mysqladmin ping succeeds" "docker exec jl-mysql failed"
fi

# 4b. Flyway-owned catalog data. MYSQL_PWD is passed via exec env, never on the
# command line or in output.
catalog_count="$(compose exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql \
  mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B \
  -e 'SELECT COUNT(*) FROM catalog_product;' 2>/dev/null | tr -d '\r\n ')" || true
if [[ "$catalog_count" == "20" ]]; then
  pass "mysql catalog_product has exactly 20 seeded rows"
else
  fail "mysql catalog_product has exactly 20 seeded rows" "count='${catalog_count}'"
fi

flyway_versions="$(compose exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql \
  mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B \
  -e 'SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success = 1;' \
  2>/dev/null | tr -d '\r\n ')" || true
if [[ "$flyway_versions" == "1,2" ]]; then
  pass "flyway applied migrations 1,2 successfully"
else
  fail "flyway applied migrations 1,2 successfully" "versions='${flyway_versions}'"
fi

# 5. Redis liveness.
redis_reply="$(docker exec jl-redis redis-cli ping 2>/dev/null | tr -d '\r\n' || true)"
if [[ "$redis_reply" == "PONG" ]]; then
  pass "redis-cli ping returns PONG"
else
  fail "redis-cli ping returns PONG" "got '${redis_reply}'"
fi

# 6. Exporter endpoints scraped from inside the Nginx container (compose network).
check_metric "redis exporter exposes redis_up 1" \
  "http://redis-exporter:9121/metrics" "redis_up 1"
check_metric "mysqld exporter exposes mysql_up 1" \
  "http://mysqld-exporter:9104/metrics" "mysql_up 1"
check_metric "nginx exporter exposes nginx_connections_active" \
  "http://nginx-exporter:9113/metrics" "nginx_connections_active"
check_metric "rabbitmq prometheus plugin exposes rabbitmq_ metrics" \
  "http://rabbitmq:15692/metrics" "rabbitmq_"

# 7. Observability endpoints (internal only).
check_http_ok "prometheus /-/ready reachable internally" \
  "http://prometheus:9090/-/ready"
check_http_ok "tempo /ready reachable internally" \
  "http://tempo:3200/ready"
check_http_ok "otel collector health endpoint (13133) reachable internally" \
  "http://otel-collector:13133/"
check_http_ok "cadvisor /healthz reachable internally" \
  "http://cadvisor:8080/healthz"

# 8. Grafana health and provisioning.
grafana_health_body=""
for attempt in $(seq 1 20); do
  grafana_health_body="$(curl -fsS --max-time 5 http://127.0.0.1:3000/api/health 2>/dev/null || true)"
  if grep -Eq '"database"[[:space:]]*:[[:space:]]*"ok"' <<<"$grafana_health_body"; then
    break
  fi
  if (( attempt < 20 )); then
    sleep 1
  fi
done
if grep -Eq '"database"[[:space:]]*:[[:space:]]*"ok"' <<<"$grafana_health_body"; then
  pass "grafana /api/health reports database ok"
else
  fail "grafana /api/health reports database ok" "last body: ${grafana_health_body:0:160}"
fi

ds_code=""
ds_body=""
for attempt in $(seq 1 20); do
  ds_response="$(curl -sS -w $'\n%{http_code}' --max-time 10 \
    -u "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
    http://127.0.0.1:3000/api/datasources/uid/prometheus/health 2>/dev/null || true)"
  ds_code="${ds_response##*$'\n'}"
  ds_body="${ds_response%$'\n'*}"
  if [[ "$ds_code" == "200" ]] && grep -q '"status":"OK"' <<<"$ds_body"; then
    break
  fi
  if (( attempt < 20 )); then
    sleep 1
  fi
done
if [[ "$ds_code" == "200" ]] && grep -q '"status":"OK"' <<<"$ds_body"; then
  pass "grafana Prometheus datasource health is OK"
else
  fail "grafana Prometheus datasource health is OK" "HTTP ${ds_code:-000} body: ${ds_body:0:160}"
fi

for uid in ha-d1 ha-d2 ha-d3; do
  dash_code=""
  for attempt in $(seq 1 10); do
    dash_code="$(dashboard_http_code "$uid")"
    if [[ "$dash_code" == "200" ]]; then
      break
    fi
    if (( attempt < 10 )); then
      sleep 1
    fi
  done
  if [[ "$dash_code" == "200" ]]; then
    pass "grafana dashboard $uid is provisioned (HTTP 200)"
  else
    fail "grafana dashboard $uid is provisioned (HTTP 200)" "got HTTP ${dash_code:-000}"
  fi
done

# 9. Prometheus sees cAdvisor, and cAdvisor container series exist when the
#    Docker Desktop/WSL2 runtime allows them (absence is a WARN, not a failure).
cadvisor_up_body=""
for attempt in $(seq 1 30); do
  cadvisor_up_body="$(prometheus_query 'up%7Bjob%3D%22cadvisor%22%7D')"
  if grep -Eq '"value":\[[0-9.]+,"1"\]' <<<"$cadvisor_up_body"; then
    break
  fi
  if (( attempt < 30 )); then
    sleep 2
  fi
done
if grep -Eq '"value":\[[0-9.]+,"1"\]' <<<"$cadvisor_up_body"; then
  pass "prometheus query up{job=\"cadvisor\"} == 1"
else
  fail "prometheus query up{job=\"cadvisor\"} == 1" "value is not 1"
fi

cadvisor_series_body=""
for attempt in $(seq 1 15); do
  cadvisor_series_body="$(prometheus_query 'container_cpu_usage_seconds_total%7Bcontainer_label_lab%3D%22java-learn%22%7D')"
  if grep -q '"result":\[{' <<<"$cadvisor_series_body"; then
    break
  fi
  if (( attempt < 15 )); then
    sleep 2
  fi
done
if grep -q '"result":\[{' <<<"$cadvisor_series_body"; then
  pass "cadvisor exposes container series for lab containers"
else
  warn "cadvisor container series absent (Docker Desktop/WSL2 limitation; D3 CPU/memory/network panels will show No data)"
fi

# 10. Application targets, Micrometer series and OTel traces.
app_up_body=""
for attempt in $(seq 1 30); do
  app_up_body="$(prometheus_query 'count%28up%7Bjob%3D%22seckill-app%22%7D%3D%3D1%29')"
  if grep -Eq '"value":\[[0-9.]+,"3"\]' <<<"$app_up_body"; then
    break
  fi
  if (( attempt < 30 )); then
    sleep 2
  fi
done
if grep -Eq '"value":\[[0-9.]+,"3"\]' <<<"$app_up_body"; then
  pass "prometheus count(up{job=\"seckill-app\"} == 1) == 3"
else
  fail "prometheus count(up{job=\"seckill-app\"} == 1) == 3" \
    "response: ${app_up_body:0:160}"
fi

check_metric "prometheus scrapes http_server_requests_seconds_bucket for seckill-app" \
  "http://prometheus:9090/api/v1/query?query=http_server_requests_seconds_bucket%7Bjob%3D%22seckill-app%22%7D" \
  '"job":"seckill-app"'
check_metric "prometheus scrapes jvm_threads_live_threads for seckill-app" \
  "http://prometheus:9090/api/v1/query?query=jvm_threads_live_threads%7Bjob%3D%22seckill-app%22%7D" \
  '"job":"seckill-app"'

# Catalog datasource pool and URI-template metrics (Task 12).
hikari_body=""
for attempt in $(seq 1 30); do
  hikari_body="$(prometheus_query 'hikaricp_connections_active%7Bjob%3D%22seckill-app%22%7D')"
  if grep -q '"job":"seckill-app"' <<<"$hikari_body"; then
    break
  fi
  if (( attempt < 30 )); then
    sleep 2
  fi
done
if grep -q '"job":"seckill-app"' <<<"$hikari_body"; then
  pass "prometheus has hikaricp_connections_active{job=\"seckill-app\"}"
else
  fail "prometheus has hikaricp_connections_active{job=\"seckill-app\"}" \
    "response: ${hikari_body:0:160}"
fi

product_uri_body=""
for attempt in $(seq 1 30); do
  product_uri_body="$(prometheus_query 'http_server_requests_seconds_count%7Bjob%3D%22seckill-app%22%2Curi%3D%22%2Fapi%2Fproducts%2F%7Bid%7D%22%7D')"
  if grep -q '"uri":"/api/products/{id}"' <<<"$product_uri_body" \
    && grep -Eq '"value":\[[0-9.]+,"[1-9][0-9]*"\]' <<<"$product_uri_body"; then
    break
  fi
  if (( attempt < 30 )); then
    sleep 2
  fi
done
if grep -q '"uri":"/api/products/{id}"' <<<"$product_uri_body" \
  && grep -Eq '"value":\[[0-9.]+,"[1-9][0-9]*"\]' <<<"$product_uri_body"; then
  pass "prometheus has http_server_requests_seconds_count{uri=\"/api/products/{id}\"} > 0"
else
  fail "prometheus has http_server_requests_seconds_count{uri=\"/api/products/{id}\"} > 0" \
    "response: ${product_uri_body:0:200}"
fi

# The OTel agent + collector batch exports, so allow up to 30s for a trace to land.
# start/end (epoch seconds) restrict the search to traces from this run: Tempo
# persists traces across restarts, so an old trace must never satisfy the check.
tempo_window_end="$(( $(date +%s) + 5 ))"
tempo_trace_body=""
for attempt in $(seq 1 30); do
  tempo_trace_body="$(docker exec jl-nginx wget -qO- -T 5 \
    "http://tempo:3200/api/search?tags=service.name%3Dseckill-app&start=${hello_window_start}&end=${tempo_window_end}&limit=5" 2>/dev/null || true)"
  if grep -q '"traceID"' <<<"$tempo_trace_body"; then
    break
  fi
  if (( attempt < 30 )); then
    sleep 1
  fi
done
if grep -q '"traceID"' <<<"$tempo_trace_body"; then
  tempo_trace_id="$(sed -n 's/.*"traceID"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$tempo_trace_body" | head -n 1)"
  pass "tempo has a fresh trace for service.name=seckill-app (${tempo_trace_id}, start=${hello_window_start} end=${tempo_window_end})"
else
  fail "tempo has a fresh trace for service.name=seckill-app" \
    "no trace in start=${hello_window_start} end=${tempo_window_end}; response: ${tempo_trace_body:0:160}"
fi

# 11. Lab console and chaos controller (Task 11).
# The console must be a real built page, the controller must be reachable
# through Nginx, and only the lab=java-learn whitelist may be exposed.
lab_page_response="$(curl -sS -w $'\n%{http_code}' --max-time 5 \
  http://127.0.0.1:8080/lab/ 2>/dev/null || true)"
lab_page_code="${lab_page_response##*$'\n'}"
lab_page_body="${lab_page_response%$'\n'*}"
if [[ "$lab_page_code" == "200" ]] && grep -qi '<div id="app"' <<<"$lab_page_body"; then
  pass "lab console /lab/ returns 200 HTML"
else
  fail "lab console /lab/ returns 200 HTML" "HTTP ${lab_page_code:-000}"
fi

lab_health_body="$(curl -fsS --max-time 5 http://127.0.0.1:8080/lab-api/health 2>/dev/null || true)"
if grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$lab_health_body"; then
  pass "lab controller /lab-api/health is UP without a token"
else
  fail "lab controller /lab-api/health is UP without a token" \
    "body: ${lab_health_body:0:120}"
fi

lab_unauth_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
  http://127.0.0.1:8080/lab-api/containers 2>/dev/null || true)"
if [[ "$lab_unauth_code" == "401" ]]; then
  pass "lab controller rejects /lab-api/containers without a token (401)"
else
  fail "lab controller rejects /lab-api/containers without a token (401)" \
    "got HTTP ${lab_unauth_code:-000}"
fi

lab_containers_response="$(curl -sS -w $'\n%{http_code}' --max-time 10 \
  -H "X-Lab-Token: ${LAB_TOKEN}" \
  http://127.0.0.1:8080/lab-api/containers 2>/dev/null || true)"
lab_containers_code="${lab_containers_response##*$'\n'}"
lab_containers_body="${lab_containers_response%$'\n'*}"
lab_names="$(printf '%s' "$lab_containers_body" | grep -o '"name":"[^"]*"' \
  | sed 's/^"name":"//; s/"$//' || true)"
if [[ "$lab_containers_code" == "200" ]] \
  && grep -q '"name":"jl-app-1"' <<<"$lab_containers_body"; then
  if [[ -n "$lab_names" ]] \
    && ! grep -qv '^jl-' <<<"$lab_names" \
    && ! grep -qx 'redis' <<<"$lab_names"; then
    pass "authorized /lab-api/containers lists only jl-* lab containers (includes jl-app-1)"
  else
    fail "authorized /lab-api/containers lists only jl-* lab containers (includes jl-app-1)" \
      "names: $(printf '%s' "$lab_names" | tr '\n' ' ')"
  fi
else
  fail "authorized /lab-api/containers lists only jl-* lab containers (includes jl-app-1)" \
    "HTTP ${lab_containers_code:-000} body: ${lab_containers_body:0:160}"
fi

lab_logs_response="$(curl -sS -w $'\n%{http_code}' --max-time 15 \
  -H "X-Lab-Token: ${LAB_TOKEN}" \
  "http://127.0.0.1:8080/lab-api/logs/jl-app-1?tail=20" 2>/dev/null || true)"
lab_logs_code="${lab_logs_response##*$'\n'}"
lab_logs_body="${lab_logs_response%$'\n'*}"
if [[ "$lab_logs_code" == "200" && -n "$lab_logs_body" ]]; then
  pass "authorized /lab-api/logs/jl-app-1?tail=20 returns non-empty text"
else
  fail "authorized /lab-api/logs/jl-app-1?tail=20 returns non-empty text" \
    "HTTP ${lab_logs_code:-000}, bytes=$(printf '%s' "$lab_logs_body" | wc -c | tr -d ' ')"
fi

chaos_up_body=""
for attempt in $(seq 1 30); do
  chaos_up_body="$(prometheus_query 'up%7Bjob%3D%22chaos-controller%22%7D')"
  if grep -Eq '"value":\[[0-9.]+,"1"\]' <<<"$chaos_up_body"; then
    break
  fi
  if (( attempt < 30 )); then
    sleep 2
  fi
done
if grep -Eq '"value":\[[0-9.]+,"1"\]' <<<"$chaos_up_body"; then
  pass "prometheus query up{job=\"chaos-controller\"} == 1"
else
  fail "prometheus query up{job=\"chaos-controller\"} == 1" \
    "response: ${chaos_up_body:0:160}"
fi

if (( FAILED > 0 )); then
  echo
  echo "VERIFY FAILED: $FAILED check(s) failed, $PASSED passed, $WARNED warning(s)." >&2
  echo "--- docker compose ps ---" >&2
  compose ps >&2 || true
  echo "--- docker compose logs (last 50 lines) ---" >&2
  compose logs --tail=50 >&2 || true
  if $CLEANUP; then
    echo "--cleanup requested, but cleanup was skipped because verification failed." >&2
  fi
  echo "The stack was left running for debugging; run '$DOWN_SCRIPT' when finished." >&2
  exit 1
fi

echo
echo "VERIFY PASSED: $PASSED checks passed, $WARNED warning(s)."

if $CLEANUP; then
  # Required clean cycle: stop the stack, keep the volumes, prove compose ps is empty.
  echo "Stopping the stack to prove the clean cycle (--cleanup)..."
  if ! bash "$DOWN_SCRIPT"; then
    echo "ERROR: down.sh failed." >&2
    exit 1
  fi

  if ! remaining="$(compose ps -a --format '{{.Name}}' 2>/dev/null)"; then
    fail "compose ps runs after down" "docker compose ps failed"
    exit 1
  fi
  if [[ -n "$remaining" ]]; then
    fail "compose project is empty after down" "remaining: $(echo "$remaining" | tr '\n' ' ')"
    compose ps -a >&2 || true
    exit 1
  fi
  pass "compose project is empty after down"

  volumes_ok=true
  lab_volumes=(jl-mysql-data jl-redis-data jl-rabbitmq-data jl-prometheus-data jl-grafana-data jl-tempo-data)
  for volume in "${lab_volumes[@]}"; do
    if ! docker volume inspect "$volume" >/dev/null 2>&1; then
      volumes_ok=false
      fail "named volume $volume preserved" "volume missing"
    fi
  done
  if $volumes_ok; then
    pass "named volumes preserved (${lab_volumes[*]})"
  else
    echo "VERIFY FAILED: one or more named volumes are missing after down." >&2
    exit 1
  fi

  echo
  echo "CLEAN EXIT: verified, stopped, compose project empty, volumes preserved."
else
  echo "Stack left running for debugging (default)."
  echo "  Grafana: http://127.0.0.1:3000 (user: ${GRAFANA_ADMIN_USER}, pass: ${GRAFANA_ADMIN_PASSWORD})"
  echo "Stop with: bash $DOWN_SCRIPT"
fi
