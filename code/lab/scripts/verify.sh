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

# Async seckill verify settings (Task 16). Product 20 is reserved for this path;
# product 1 stays dedicated to the synchronous order checks above.
SECKILL_PRODUCT_ID="${SECKILL_PRODUCT_ID:-20}"
SECKILL_POLL_RETRIES="${SECKILL_POLL_RETRIES:-30}"
SECKILL_POLL_INTERVAL_SECONDS="${SECKILL_POLL_INTERVAL_SECONDS:-1}"
SECKILL_QUEUE_NAME="${SECKILL_QUEUE_NAME:-seckill.order.queue}"
SECKILL_STOCK_KEY_PREFIX="${SECKILL_STOCK_KEY_PREFIX:-seckill:stock:}"
RABBITMQ_MANAGEMENT_URL="${RABBITMQ_MANAGEMENT_URL:-http://127.0.0.1:15672}"
# curl timeouts for the seckill checks: POST/repeat/queue management use the
# general budget, GET polling uses the shorter per-poll budget.
SECKILL_CURL_MAX_TIME="${SECKILL_CURL_MAX_TIME:-10}"
SECKILL_CURL_POLL_MAX_TIME="${SECKILL_CURL_POLL_MAX_TIME:-5}"

# Task 17: mock login and the internal-only lab API.
LOGIN_TOKEN_KEY_PREFIX="${LOGIN_TOKEN_KEY_PREFIX:-login:token:}"
LOGIN_CURL_MAX_TIME="${LOGIN_CURL_MAX_TIME:-5}"
LAB_INTERNAL_BASE_URL="${LAB_INTERNAL_BASE_URL:-http://127.0.0.1:8080}"
# Per-request curl budget for the Nginx lab-path probe and the polling budget
# for the resilience4j metric series to appear in Prometheus (scrape interval
# 5s, so a few scrape cycles are allowed by default).
LAB_NGINX_CURL_MAX_TIME="${LAB_NGINX_CURL_MAX_TIME:-5}"
RESILIENCE_POLL_RETRIES="${RESILIENCE_POLL_RETRIES:-30}"
RESILIENCE_POLL_INTERVAL_SECONDS="${RESILIENCE_POLL_INTERVAL_SECONDS:-2}"
LAB_TOGGLE_KEYS=(lock idempotency mq ratelimit circuitbreaker cache)

# Task 18: Prometheus metric visibility window (scrape interval is 5s).
METRIC_WAIT_RETRIES="${METRIC_WAIT_RETRIES:-30}"
METRIC_WAIT_INTERVAL_SECONDS="${METRIC_WAIT_INTERVAL_SECONDS:-2}"

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

# Wait for a Prometheus instant query to report a positive scalar value.
check_prometheus_positive() {
  local label="$1" encoded_query="$2" body="" attempt
  for attempt in $(seq 1 "$METRIC_WAIT_RETRIES"); do
    body="$(prometheus_query "$encoded_query")"
    if grep -Eq '"value":\[[0-9.]+,"[1-9][0-9]*(\.[0-9]+)?"\]' <<<"$body"; then
      pass "$label"
      return 0
    fi
    if (( attempt < METRIC_WAIT_RETRIES )); then
      sleep "$METRIC_WAIT_INTERVAL_SECONDS"
    fi
  done
  fail "$label" "response: ${body:0:200}"
}

# Query the Prometheus HTTP API from inside jl-net. The caller passes a
# percent-encoded query expression so no host-side URL encoding is required.
prometheus_query() {
  local encoded_query="$1"
  docker exec jl-nginx wget -qO- -T 5 \
    "http://prometheus:9090/api/v1/query?query=${encoded_query}" 2>/dev/null || true
}

# Scalar MySQL query via the compose model (credentials never reach the host
# command line). Strip CR/LF so values compare cleanly in [[ ]].
mysql_scalar() {
  local statement="$1"
  compose exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B \
    -e "$statement" 2>/dev/null | tr -d '\r\n ' || true
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

# 2b-bis. Read/write datasource routing (Task 15).
# The write pool must be visible before any forced read (Flyway and the health
# checks use it). Never fall back to accepting only the write pool: the read
# pool has a different pool tag and is only initialized when a read-only
# transaction acquires a connection, so a cache miss is forced through product
# ids that no earlier check requested. Redis L2 keys are deleted first; the
# 60s Caffeine L1 cannot contain these ids on a freshly restarted stack.
check_prometheus_positive \
  "prometheus has hikaricp_connections_max{job=\"seckill-app\",pool=\"write-pool\"} > 0" \
  'hikaricp_connections_max%7Bjob%3D%22seckill-app%22%2Cpool%3D%22write-pool%22%7D'

routing_reads_ok=true
routing_read_code=""
routing_read_body=""
for routing_product_id in 2 3 4; do
  docker exec jl-redis redis-cli del "cache:product:${routing_product_id}" >/dev/null 2>&1 || true
  routing_read_response="$(curl -sS -w $'\n%{http_code}' --max-time 5 \
    "http://127.0.0.1:8080/api/products/${routing_product_id}" 2>/dev/null || true)"
  routing_read_code="${routing_read_response##*$'\n'}"
  routing_read_body="${routing_read_response%$'\n'*}"
  if [[ "$routing_read_code" != "200" ]] \
    || ! grep -Eq "\"id\"[[:space:]]*:[[:space:]]*${routing_product_id}([^0-9]|$)" <<<"$routing_read_body"; then
    routing_reads_ok=false
    break
  fi
done
if $routing_reads_ok; then
  pass "cold product reads for ids 2,3,4 return 200 (each call bypasses cache and reads the database)"
else
  fail "cold product reads for ids 2,3,4 return 200 (each call bypasses cache and reads the database)" \
    "HTTP ${routing_read_code:-000} body: ${routing_read_body:0:160}"
fi

check_prometheus_positive \
  "prometheus has hikaricp_connections_max{job=\"seckill-app\",pool=\"read-pool\"} > 0 (read pool initialized by a read-only transaction)" \
  'hikaricp_connections_max%7Bjob%3D%22seckill-app%22%2Cpool%3D%22read-pool%22%7D'

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

# 2c-bis. Two-level product cache (Task 14): Caffeine L1 + Redis L2 behind
# Spring Cache. The calls above already warmed L1 on each instance and wrote
# the L2 key; another round proves L1 hits and keeps the cache observable.
cache_calls_ok=true
cache_call_code=""
for _ in $(seq 1 6); do
  cache_call_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    http://127.0.0.1:8080/api/products/1 2>/dev/null || true)"
  if [[ "$cache_call_code" != "200" ]]; then
    cache_calls_ok=false
    break
  fi
done
if $cache_calls_ok; then
  pass "six /api/products/1 calls through nginx succeed while warming the cache"
else
  fail "six /api/products/1 calls through nginx succeed while warming the cache" \
    "HTTP ${cache_call_code:-000}"
fi

check_prometheus_positive \
  "prometheus has cache_l1_requests_total{cache=\"product\",result=\"hit\"} > 0" \
  'sum%28cache_l1_requests_total%7Bjob%3D%22seckill-app%22%2Ccache%3D%22product%22%2Cresult%3D%22hit%22%7D%29'
check_prometheus_positive \
  "prometheus has cache_l1_requests_total{cache=\"product\",result=\"miss\"} > 0" \
  'sum%28cache_l1_requests_total%7Bjob%3D%22seckill-app%22%2Ccache%3D%22product%22%2Cresult%3D%22miss%22%7D%29'
check_prometheus_positive \
  "prometheus has cache_l2_requests_total{cache=\"product\",result=\"hit\"} > 0" \
  'sum%28cache_l2_requests_total%7Bjob%3D%22seckill-app%22%2Ccache%3D%22product%22%2Cresult%3D%22hit%22%7D%29'
check_prometheus_positive \
  "prometheus has cache_l2_requests_total{cache=\"product\",result=\"miss\"} > 0" \
  'sum%28cache_l2_requests_total%7Bjob%3D%22seckill-app%22%2Ccache%3D%22product%22%2Cresult%3D%22miss%22%7D%29'

cache_keys="$(docker exec jl-redis redis-cli --scan --pattern 'cache:product:*' 2>/dev/null \
  | tr -d '\r' || true)"
if [[ -n "$cache_keys" ]]; then
  cache_key_count="$(printf '%s\n' "$cache_keys" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
  pass "redis has $cache_key_count cache:product:* key(s) written by the L2 cache"
  cache_key="$(printf '%s\n' "$cache_keys" | sed '/^[[:space:]]*$/d' | head -n 1)"
  cache_ttl="$(docker exec jl-redis redis-cli ttl "$cache_key" 2>/dev/null | tr -d '\r' || true)"
  if [[ "$cache_ttl" =~ ^[0-9]+$ ]] && (( cache_ttl > 0 )); then
    pass "redis TTL for $cache_key is positive (${cache_ttl}s)"
  else
    fail "redis TTL for $cache_key is positive" "ttl='${cache_ttl}'"
  fi
else
  fail "redis has at least one cache:product:* key" "scan returned no keys"
  fail "redis TTL for a cache:product:* key is positive" "no cache key to inspect"
fi

# The compose app healthcheck gates on the "app" health group, which must be
# live, UP, and must exclude redis (best-effort L2; design §8.2 failover demos
# require the app tier to stay healthy during a Redis outage). A regression
# here would otherwise only surface ~100s later as "unhealthy" containers.
for app_container in jl-app-1 jl-app-2 jl-app-3; do
  gate_body="$(docker exec "$app_container" \
    wget -qO- http://127.0.0.1:8080/actuator/health/app 2>/dev/null | tr -d '\r\n' || true)"
  if grep -q '"status":"UP"' <<<"$gate_body" && ! grep -q '"redis"' <<<"$gate_body"; then
    pass "$app_container /actuator/health/app is UP and excludes redis (container gate)"
  else
    fail "$app_container /actuator/health/app is UP and excludes redis (container gate)" \
      "body: ${gate_body:0:160}"
  fi
done

# The L2 error counter must be exported even at zero, so outage dashboards and
# alerts can bind to it (a typo'd meter name fails here, not in M3).
# Capture first: `grep -q` closes the pipe early and pipefail would then report
# wget's SIGPIPE as a failed check even when the series matched.
cache_metrics_body="$(docker exec jl-app-1 wget -qO- \
  http://127.0.0.1:8080/actuator/prometheus 2>/dev/null || true)"
if grep -q '^cache_l2_errors_total' <<<"$cache_metrics_body"; then
  pass "app-1 exposes cache_l2_errors_total on /actuator/prometheus"
else
  fail "app-1 exposes cache_l2_errors_total on /actuator/prometheus" "series missing"
fi

# 2d. Inventory + idempotent order flow through Nginx (Task 13). The three
# instances share one database, so the retried POST may hit a different app.
inventory_rows="$(mysql_scalar 'SELECT COUNT(*) FROM inventory_stock;')"
if [[ "$inventory_rows" == "20" ]]; then
  pass "mysql inventory_stock has one row per catalog product (20)"
else
  fail "mysql inventory_stock has one row per catalog product (20)" "count='${inventory_rows}'"
fi

stock_before="$(mysql_scalar 'SELECT available FROM inventory_stock WHERE product_id = 1;')"
orders_before="$(mysql_scalar 'SELECT COUNT(*) FROM order_order;')"
if [[ "$stock_before" =~ ^[0-9]+$ && "$orders_before" =~ ^[0-9]+$ ]]; then
  pass "mysql inventory/order tables are queryable (stock=${stock_before}, orders=${orders_before})"
else
  fail "mysql inventory/order tables are queryable (stock=${stock_before}, orders=${orders_before})" \
    "unexpected scalar values"
fi

order_key="verify-$(date +%s)-${RANDOM}-$$"
order_payload='{"userId":"verify-user","productId":1,"quantity":1}'
order_response="$(curl -sS -w $'\n%{http_code}' --max-time 10 \
  -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: ${order_key}" \
  --data "$order_payload" \
  http://127.0.0.1:8080/api/orders 2>/dev/null || true)"
order_http_code="${order_response##*$'\n'}"
order_body="${order_response%$'\n'*}"
order_id="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' <<<"$order_body" | head -n 1)"
order_create_ok=false
if [[ "$order_http_code" == "200" ]] \
  && [[ "$order_id" =~ ^[0-9]+$ ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$order_body"; then
  order_create_ok=true
  pass "nginx POST /api/orders creates an order (id=${order_id}, key=${order_key})"
else
  fail "nginx POST /api/orders creates an order (key=${order_key})" \
    "HTTP ${order_http_code:-000} body: ${order_body:0:200}"
fi

if $order_create_ok; then
  repeat_response="$(curl -sS -w $'\n%{http_code}' --max-time 10 \
    -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: ${order_key}" \
    --data "$order_payload" \
    http://127.0.0.1:8080/api/orders 2>/dev/null || true)"
  repeat_http_code="${repeat_response##*$'\n'}"
  repeat_body="${repeat_response%$'\n'*}"
  repeat_id="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' <<<"$repeat_body" | head -n 1)"
  orders_after_repeat="$(mysql_scalar 'SELECT COUNT(*) FROM order_order;')"
  stock_after_repeat="$(mysql_scalar 'SELECT available FROM inventory_stock WHERE product_id = 1;')"
  if [[ "$repeat_http_code" == "200" && "$repeat_id" == "$order_id" ]] \
    && [[ "$orders_after_repeat" == "$((orders_before + 1))" ]] \
    && [[ "$stock_after_repeat" == "$((stock_before - 1))" ]]; then
    pass "repeating the same Idempotency-Key returns order ${order_id} with no extra order or stock decrement"
  else
    fail "repeating the same Idempotency-Key returns order ${order_id} with no extra order or stock decrement" \
      "HTTP ${repeat_http_code:-000} id='${repeat_id}' orders='${orders_after_repeat}' stock='${stock_after_repeat}'"
  fi

  order_get_response="$(curl -sS -w $'\n%{http_code}' --max-time 5 \
    "http://127.0.0.1:8080/api/orders/${order_id}" 2>/dev/null || true)"
  order_get_code="${order_get_response##*$'\n'}"
  order_get_body="${order_get_response%$'\n'*}"
  order_get_id="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' <<<"$order_get_body" | head -n 1)"
  if [[ "$order_get_code" == "200" && "$order_get_id" == "$order_id" ]] \
    && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$order_get_body"; then
    pass "nginx GET /api/orders/${order_id} returns 200 with the created order"
  else
    fail "nginx GET /api/orders/${order_id} returns 200 with the created order" \
      "HTTP ${order_get_code:-000} id='${order_get_id}' body: ${order_get_body:0:200}"
  fi

  orders_final="$(mysql_scalar 'SELECT COUNT(*) FROM order_order;')"
  stock_final="$(mysql_scalar 'SELECT available FROM inventory_stock WHERE product_id = 1;')"
  min_stock="$(mysql_scalar 'SELECT MIN(available) FROM inventory_stock;')"
  if [[ "$orders_final" == "$((orders_before + 1))" ]] \
    && [[ "$stock_final" == "$((stock_before - 1))" ]] \
    && [[ "$min_stock" =~ ^[0-9]+$ ]]; then
    pass "inventory for product 1 decreased by exactly one run order (${stock_before} -> ${stock_final}, min=${min_stock}, orders=${orders_final})"
  else
    fail "inventory for product 1 decreased by exactly one run order (${stock_before} -> ${stock_final}, min=${min_stock}, orders=${orders_final})" \
      "unexpected stock/order delta"
  fi

  # Business counter must advance only for the real insert, not the replay.
  # Prometheus scrapes the app every 5s; allow a short visibility window.
  orders_created_body=""
  for attempt in $(seq 1 30); do
    orders_created_body="$(prometheus_query 'sum%28orders_created_total%7Bjob%3D%22seckill-app%22%7D%29')"
    if grep -Eq '"value":\[[0-9.]+,"[1-9][0-9]*(\.[0-9]+)?"\]' <<<"$orders_created_body"; then
      break
    fi
    if (( attempt < 30 )); then
      sleep 2
    fi
  done
  if grep -Eq '"value":\[[0-9.]+,"[1-9][0-9]*(\.[0-9]+)?"\]' <<<"$orders_created_body"; then
    pass "prometheus has orders_created_total{job=\"seckill-app\"} > 0 after a real insert"
  else
    fail "prometheus has orders_created_total{job=\"seckill-app\"} > 0 after a real insert" \
      "response: ${orders_created_body:0:200}"
  fi
else
  fail "repeat/query/stock checks for POST /api/orders" "order creation failed; dependent checks skipped"
fi

# 2e. Async seckill admission path (Task 16): product 20 only, so the
# synchronous order checks above (product 1) and this path never share stock.
seckill_stock_key="${SECKILL_STOCK_KEY_PREFIX}${SECKILL_PRODUCT_ID}"
seckill_db_before="$(mysql_scalar "SELECT available FROM inventory_stock WHERE product_id = ${SECKILL_PRODUCT_ID};")"
seckill_redis_before="$(docker exec jl-redis redis-cli get "$seckill_stock_key" 2>/dev/null \
  | tr -d '\r\n' || true)"
if [[ "$seckill_db_before" =~ ^[0-9]+$ ]] && [[ "$seckill_redis_before" =~ ^[0-9]+$ ]]; then
  pass "seckill stock for product ${SECKILL_PRODUCT_ID} exists (mysql=${seckill_db_before}, redis=${seckill_redis_before})"
else
  fail "seckill stock for product ${SECKILL_PRODUCT_ID} exists" \
    "mysql='${seckill_db_before}' redis='${seckill_redis_before}'"
fi

seckill_key="verify-seckill-$(date +%s)-${RANDOM}-$$"
seckill_payload="{\"userId\":\"verify-seckill-user\",\"productId\":${SECKILL_PRODUCT_ID},\"quantity\":1}"
seckill_accept_response="$(curl -sS -w $'\n%{http_code}' --max-time "$SECKILL_CURL_MAX_TIME" \
  -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: ${seckill_key}" \
  --data "$seckill_payload" \
  http://127.0.0.1:8080/api/seckill/orders 2>/dev/null || true)"
seckill_accept_code="${seckill_accept_response##*$'\n'}"
seckill_accept_body="${seckill_accept_response%$'\n'*}"
seckill_request_id="$(sed -n 's/.*"requestId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  <<<"$seckill_accept_body" | head -n 1)"
if [[ "$seckill_accept_code" == "202" ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$seckill_accept_body" \
  && grep -Eq '"status"[[:space:]]*:[[:space:]]*"ACCEPTED"' <<<"$seckill_accept_body" \
  && [[ -n "$seckill_request_id" ]]; then
  pass "nginx POST /api/seckill/orders returns 202 ACCEPTED with requestId=${seckill_request_id}"
else
  fail "nginx POST /api/seckill/orders returns 202 ACCEPTED with requestId" \
    "HTTP ${seckill_accept_code:-000} body: ${seckill_accept_body:0:240}"
fi

seckill_poll_code=""
seckill_poll_status=""
seckill_order_id=""
if [[ -n "$seckill_request_id" ]]; then
  for attempt in $(seq 1 "$SECKILL_POLL_RETRIES"); do
    seckill_poll_response="$(curl -sS -w $'\n%{http_code}' --max-time "$SECKILL_CURL_POLL_MAX_TIME" \
      "http://127.0.0.1:8080/api/seckill/orders/${seckill_request_id}" 2>/dev/null || true)"
    seckill_poll_code="${seckill_poll_response##*$'\n'}"
    seckill_poll_body="${seckill_poll_response%$'\n'*}"
    seckill_poll_status="$(sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      <<<"$seckill_poll_body" | head -n 1)"
    seckill_order_id="$(sed -n 's/.*"orderId"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
      <<<"$seckill_poll_body" | head -n 1)"
    if [[ "$seckill_poll_code" == "200" ]] \
      && [[ "$seckill_poll_status" == "CREATED" ]] \
      && [[ "$seckill_order_id" =~ ^[0-9]+$ ]]; then
      break
    fi
    if (( attempt < SECKILL_POLL_RETRIES )); then
      sleep "$SECKILL_POLL_INTERVAL_SECONDS"
    fi
  done
fi
if [[ "$seckill_poll_code" == "200" ]] \
  && [[ "$seckill_poll_status" == "CREATED" ]] \
  && [[ "$seckill_order_id" =~ ^[0-9]+$ ]]; then
  pass "GET /api/seckill/orders/${seckill_request_id} reaches CREATED (orderId=${seckill_order_id})"
else
  fail "GET /api/seckill/orders/${seckill_request_id:-unknown} reaches CREATED" \
    "HTTP ${seckill_poll_code:-000} status='${seckill_poll_status}' orderId='${seckill_order_id}'"
fi

if [[ -n "$seckill_request_id" && -n "$seckill_order_id" ]]; then
  seckill_repeat_response="$(curl -sS -w $'\n%{http_code}' --max-time "$SECKILL_CURL_MAX_TIME" \
    -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: ${seckill_key}" \
    --data "$seckill_payload" \
    http://127.0.0.1:8080/api/seckill/orders 2>/dev/null || true)"
  seckill_repeat_code="${seckill_repeat_response##*$'\n'}"
  seckill_repeat_body="${seckill_repeat_response%$'\n'*}"
  seckill_repeat_request_id="$(sed -n 's/.*"requestId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    <<<"$seckill_repeat_body" | head -n 1)"
  seckill_repeat_order_id="$(sed -n 's/.*"orderId"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
    <<<"$seckill_repeat_body" | head -n 1)"
  if [[ "$seckill_repeat_code" == "202" ]] \
    && [[ "$seckill_repeat_request_id" == "$seckill_request_id" ]] \
    && [[ "$seckill_repeat_order_id" == "$seckill_order_id" ]] \
    && grep -Eq '"status"[[:space:]]*:[[:space:]]*"CREATED"' <<<"$seckill_repeat_body"; then
    pass "repeating the seckill Idempotency-Key returns request ${seckill_request_id}/order ${seckill_order_id}"
  else
    fail "repeating the seckill Idempotency-Key returns the same request/order" \
      "HTTP ${seckill_repeat_code:-000} requestId='${seckill_repeat_request_id}' orderId='${seckill_repeat_order_id}'"
  fi
else
  fail "repeating the seckill Idempotency-Key returns the same request/order" \
    "accept/poll did not yield a request/order id"
fi

seckill_db_after="$(mysql_scalar "SELECT available FROM inventory_stock WHERE product_id = ${SECKILL_PRODUCT_ID};")"
seckill_redis_after="$(docker exec jl-redis redis-cli get "$seckill_stock_key" 2>/dev/null \
  | tr -d '\r\n' || true)"
seckill_order_rows="$(mysql_scalar "SELECT COUNT(*) FROM order_order WHERE idempotency_key = '${seckill_key}';")"
if [[ "$seckill_db_before" =~ ^[0-9]+$ ]] \
  && [[ "$seckill_redis_before" =~ ^[0-9]+$ ]] \
  && [[ "$seckill_db_after" == "$((seckill_db_before - 1))" ]] \
  && [[ "$seckill_redis_after" == "$((seckill_redis_before - 1))" ]] \
  && [[ "$seckill_order_rows" == "1" ]]; then
  pass "seckill product ${SECKILL_PRODUCT_ID} decreased exactly one in mysql (${seckill_db_before} -> ${seckill_db_after}) and redis (${seckill_redis_before} -> ${seckill_redis_after}) with one order"
else
  fail "seckill product ${SECKILL_PRODUCT_ID} decreased exactly one in mysql and redis with one order" \
    "mysql '${seckill_db_before}'->'${seckill_db_after}' redis '${seckill_redis_before}'->'${seckill_redis_after}' orders='${seckill_order_rows}'"
fi

seckill_queue_exists=false
seckill_queue_ready=""
for attempt in $(seq 1 "$SECKILL_POLL_RETRIES"); do
  seckill_queue_json="$(curl -sS --max-time "$SECKILL_CURL_MAX_TIME" \
    -u "${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}" \
    "${RABBITMQ_MANAGEMENT_URL}/api/queues/%2F/${SECKILL_QUEUE_NAME}" 2>/dev/null || true)"
  if grep -q "\"name\":\"${SECKILL_QUEUE_NAME}\"" <<<"$seckill_queue_json"; then
    seckill_queue_exists=true
    seckill_queue_ready="$(sed -n 's/.*"messages_ready"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
      <<<"$seckill_queue_json" | head -n 1)"
    if [[ "$seckill_queue_ready" == "0" ]]; then
      break
    fi
  fi
  if (( attempt < SECKILL_POLL_RETRIES )); then
    sleep "$SECKILL_POLL_INTERVAL_SECONDS"
  fi
done
if $seckill_queue_exists && [[ "$seckill_queue_ready" == "0" ]]; then
  pass "rabbitmq queue ${SECKILL_QUEUE_NAME} exists and messages_ready returned to 0"
else
  fail "rabbitmq queue ${SECKILL_QUEUE_NAME} exists and messages_ready returned to 0" \
    "exists=${seckill_queue_exists} messages_ready='${seckill_queue_ready}'"
fi

seckill_metrics="$(docker exec jl-app-1 wget -qO- http://127.0.0.1:8080/actuator/prometheus \
  2>/dev/null || true)"
if grep -q '^seckill_attempts_total' <<<"$seckill_metrics" \
  && grep -q '^order_accept_seconds_count' <<<"$seckill_metrics" \
  && grep -q '^order_accept_seconds_bucket' <<<"$seckill_metrics" \
  && grep -q '^order_consumer_processed_total' <<<"$seckill_metrics" \
  && grep -q '^idempotent_reject_total' <<<"$seckill_metrics" \
  && grep -q '^stock_oversold_total' <<<"$seckill_metrics"; then
  pass "app-1 exposes seckill_attempts/order_accept histogram/order_consumer_processed/idempotent_reject/stock_oversold metrics"
else
  fail "app-1 exposes seckill/business metrics" "one or more metric families are missing"
fi

if grep -q 'result="rate_limited"' <<<"$seckill_metrics" \
  && grep -q 'result="degraded"' <<<"$seckill_metrics"; then
  pass "app-1 registers seckill_attempts result=rate_limited and result=degraded"
else
  fail "app-1 registers seckill_attempts result=rate_limited and result=degraded" \
    "one or both Task 17 result series are missing"
fi

# 2e-bis. Task 18 business metrics must reach Prometheus. The repeated
# Idempotency-Key above is the positive proof for the reject counter; the
# histogram bucket appears after the seckill accept that was just recorded.
check_prometheus_positive \
  "prometheus has idempotent_reject_total{job=\"seckill-app\"} > 0 after the idempotent replay" \
  'sum%28idempotent_reject_total%7Bjob%3D%22seckill-app%22%7D%29'

for business_metric in \
  order_accept_seconds_bucket \
  order_consumer_processed_total \
  rabbitmq_queue_messages_ready; do
  business_body=""
  for attempt in $(seq 1 "$METRIC_WAIT_RETRIES"); do
    business_body="$(prometheus_query "$business_metric")"
    if grep -q "\"__name__\":\"${business_metric}\"" <<<"$business_body"; then
      break
    fi
    if (( attempt < METRIC_WAIT_RETRIES )); then
      sleep "$METRIC_WAIT_INTERVAL_SECONDS"
    fi
  done
  if grep -q "\"__name__\":\"${business_metric}\"" <<<"$business_body"; then
    pass "prometheus has ${business_metric}"
  else
    fail "prometheus has ${business_metric}" \
      "response: ${business_body:0:200}"
  fi
done

# D4 binds the backlog panel to the per-queue series; the aggregate family
# alone would leave the queue-labelled panel empty.
seckill_queue_metric_body=""
for attempt in $(seq 1 "$METRIC_WAIT_RETRIES"); do
  seckill_queue_metric_body="$(prometheus_query \
    'rabbitmq_queue_messages_ready%7Bqueue%3D%22seckill.order.queue%22%7D')"
  if grep -q '"queue":"seckill.order.queue"' <<<"$seckill_queue_metric_body"; then
    break
  fi
  if (( attempt < METRIC_WAIT_RETRIES )); then
    sleep "$METRIC_WAIT_INTERVAL_SECONDS"
  fi
done
if grep -q '"queue":"seckill.order.queue"' <<<"$seckill_queue_metric_body"; then
  pass "prometheus has rabbitmq_queue_messages_ready{queue=\"seckill.order.queue\"}"
else
  fail "prometheus has rabbitmq_queue_messages_ready{queue=\"seckill.order.queue\"}" \
    "response: ${seckill_queue_metric_body:0:200}"
fi

# 2f. Mock login and runtime lab toggles (Task 17).
login_user="verify-login-$(date +%s)-${RANDOM}-$$"
login_payload="{\"userId\":\"${login_user}\"}"
login_response="$(curl -sS -w $'\n%{http_code}' --max-time "$LOGIN_CURL_MAX_TIME" \
  -X POST -H 'Content-Type: application/json' --data "$login_payload" \
  http://127.0.0.1:8080/api/login 2>/dev/null || true)"
login_code="${login_response##*$'\n'}"
login_body="${login_response%$'\n'*}"
login_token="$(sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  <<<"$login_body" | head -n 1)"
login_expires="$(sed -n 's/.*"expiresInSeconds"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
  <<<"$login_body" | head -n 1)"
if [[ "$login_code" == "200" ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$login_body" \
  && [[ -n "$login_token" ]] \
  && [[ "$login_expires" =~ ^[0-9]+$ ]] \
  && (( login_expires > 0 )); then
  pass "nginx POST /api/login returns 200 with a token and expiresInSeconds=${login_expires}"
else
  fail "nginx POST /api/login returns 200 with a token and expiresInSeconds" \
    "HTTP ${login_code:-000} body: ${login_body:0:200}"
fi

login_key="${LOGIN_TOKEN_KEY_PREFIX}${login_token}"
login_stored="$(docker exec jl-redis redis-cli get "$login_key" 2>/dev/null | tr -d '\r\n' || true)"
login_ttl="$(docker exec jl-redis redis-cli ttl "$login_key" 2>/dev/null | tr -d '\r\n' || true)"
if [[ -n "$login_token" && "$login_stored" == "$login_user" ]] \
  && [[ "$login_ttl" =~ ^[0-9]+$ ]] && (( login_ttl > 0 )); then
  pass "redis stores ${login_key} -> ${login_user} with TTL ${login_ttl}s"
else
  fail "redis stores the login token with a positive TTL" \
    "key='${login_key}' value='${login_stored}' ttl='${login_ttl}'"
fi

# The lab API is internal only: Nginx must not proxy it (exact 404 rule).
lab_nginx_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  --max-time "$LAB_NGINX_CURL_MAX_TIME" \
  http://127.0.0.1:8080/lab/toggles 2>/dev/null || true)"
if [[ "$lab_nginx_code" == "404" ]]; then
  pass "nginx does not proxy /lab/toggles (404)"
else
  fail "nginx does not proxy /lab/toggles (404)" "got HTTP ${lab_nginx_code:-000}"
fi

# Reach the app directly inside the compose network, as the chaos-controller
# will. wget -S writes the status line to the captured stream; the body is
# discarded. No token on the wire means 401 with the Result envelope.
# pipefail + set -e: a 401 makes wget exit 1, so the pipeline needs || true.
lab_unauth_status="$(docker exec jl-app-1 sh -c \
  "wget -S -O /dev/null '${LAB_INTERNAL_BASE_URL}/lab/toggles' 2>&1" \
  | sed -n 's/.*HTTP\/1\.1 \([0-9][0-9][0-9]\).*/\1/p' | head -n 1 || true)"
if [[ "$lab_unauth_status" == "401" ]]; then
  pass "internal GET /lab/toggles without X-Lab-Token returns 401"
else
  fail "internal GET /lab/toggles without X-Lab-Token returns 401" \
    "got '${lab_unauth_status:-unknown}'"
fi

lab_auth_status="$(docker exec jl-app-1 sh -c \
  "wget -S -O /dev/null --header='X-Lab-Token: ${LAB_TOKEN}' '${LAB_INTERNAL_BASE_URL}/lab/toggles' 2>&1" \
  | sed -n 's/.*HTTP\/1\.1 \([0-9][0-9][0-9]\).*/\1/p' | head -n 1 || true)"
lab_toggles_body="$(docker exec jl-app-1 sh -c \
  "wget -qO- --header='X-Lab-Token: ${LAB_TOKEN}' '${LAB_INTERNAL_BASE_URL}/lab/toggles'" \
  2>/dev/null || true)"
lab_toggles_ok=true
for lab_key in "${LAB_TOGGLE_KEYS[@]}"; do
  if ! grep -q "\"${lab_key}\"" <<<"$lab_toggles_body"; then
    lab_toggles_ok=false
  fi
done
if [[ "$lab_auth_status" == "200" ]] && $lab_toggles_ok; then
  pass "internal GET /lab/toggles with token returns 200 and all six keys"
else
  fail "internal GET /lab/toggles with token returns 200 and all six keys" \
    "HTTP ${lab_auth_status:-000} body: ${lab_toggles_body:0:200}"
fi

lab_toggle_false_status="$(docker exec jl-app-1 sh -c \
  "wget -S -O /dev/null --header='X-Lab-Token: ${LAB_TOKEN}' --header='Content-Type: application/json' --post-data='{\"key\":\"cache\",\"value\":false}' '${LAB_INTERNAL_BASE_URL}/lab/toggles' 2>&1" \
  | sed -n 's/.*HTTP\/1\.1 \([0-9][0-9][0-9]\).*/\1/p' | head -n 1 || true)"
lab_toggle_false="$(docker exec jl-app-1 sh -c \
  "wget -qO- --header='X-Lab-Token: ${LAB_TOKEN}' --header='Content-Type: application/json' --post-data='{\"key\":\"cache\",\"value\":false}' '${LAB_INTERNAL_BASE_URL}/lab/toggles'" \
  2>/dev/null || true)"
lab_toggle_true="$(docker exec jl-app-1 sh -c \
  "wget -qO- --header='X-Lab-Token: ${LAB_TOKEN}' --header='Content-Type: application/json' --post-data='{\"key\":\"cache\",\"value\":true}' '${LAB_INTERNAL_BASE_URL}/lab/toggles'" \
  2>/dev/null || true)"
if [[ "$lab_toggle_false_status" == "200" ]] \
  && grep -Eq '"cache"[[:space:]]*:[[:space:]]*false' <<<"$lab_toggle_false" \
  && grep -Eq '"cache"[[:space:]]*:[[:space:]]*true' <<<"$lab_toggle_true"; then
  pass "internal POST /lab/toggles returns 200 and flips cache false then true (no-op experiment)"
else
  fail "internal POST /lab/toggles returns 200 and flips cache false then true (no-op experiment)" \
    "HTTP ${lab_toggle_false_status:-000} false-body: ${lab_toggle_false:0:160} true-body: ${lab_toggle_true:0:160}"
fi

lab_config_body="$(docker exec jl-app-1 sh -c \
  "wget -qO- --header='X-Lab-Token: ${LAB_TOKEN}' '${LAB_INTERNAL_BASE_URL}/lab/config'" \
  2>/dev/null || true)"
if grep -Eq '"app"[[:space:]]*:[[:space:]]*"seckill-app"' <<<"$lab_config_body" \
  && grep -Eq '"instance"[[:space:]]*:[[:space:]]*"app-1"' <<<"$lab_config_body" \
  && grep -Eq '"jvmVersion"[[:space:]]*:[[:space:]]*"21\.' <<<"$lab_config_body" \
  && grep -q '"toggles"' <<<"$lab_config_body" \
  && ! grep -qF "$LAB_TOKEN" <<<"$lab_config_body"; then
  pass "internal GET /lab/config exposes app/env/instance/JVM and toggles without secrets"
else
  fail "internal GET /lab/config exposes app/env/instance/JVM and toggles without secrets" \
    "body: ${lab_config_body:0:240}"
fi

# Resilience4j meters must reach Prometheus (the app registers both instances
# at startup, so no rate-limit/circuit-open probe is needed here).
for resilience_metric in \
  resilience4j_ratelimiter_available_permissions \
  resilience4j_circuitbreaker_state; do
  resilience_body=""
  for attempt in $(seq 1 "$RESILIENCE_POLL_RETRIES"); do
    resilience_body="$(prometheus_query "$resilience_metric")"
    if grep -q "\"__name__\":\"${resilience_metric}\"" <<<"$resilience_body"; then
      break
    fi
    if (( attempt < RESILIENCE_POLL_RETRIES )); then
      sleep "$RESILIENCE_POLL_INTERVAL_SECONDS"
    fi
  done
  if grep -q "\"__name__\":\"${resilience_metric}\"" <<<"$resilience_body"; then
    pass "prometheus has ${resilience_metric}"
  else
    fail "prometheus has ${resilience_metric}" \
      "response: ${resilience_body:0:200}"
  fi
done

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
if [[ "$flyway_versions" == "1,2,3" ]]; then
  pass "flyway applied migrations 1,2,3 successfully"
else
  fail "flyway applied migrations 1,2,3 successfully" "versions='${flyway_versions}'"
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

for uid in ha-d1 ha-d2 ha-d3 ha-d4; do
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
