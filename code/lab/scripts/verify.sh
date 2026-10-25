#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
DOWN_SCRIPT="$SCRIPT_DIR/down.sh"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run '$SCRIPT_DIR/up.sh --profile dev' first." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

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

echo "Verifying java-learn-lab..."

# 1. Containers.
for service in nginx mysql redis rabbitmq redis-exporter mysqld-exporter nginx-exporter; do
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

if (( FAILED > 0 )); then
  echo
  echo "VERIFY FAILED: $FAILED check(s) failed, $PASSED passed." >&2
  echo "--- docker compose ps ---" >&2
  compose ps >&2 || true
  echo "--- docker compose logs (last 50 lines) ---" >&2
  compose logs --tail=50 >&2 || true
  echo "The stack was left running for debugging; run '$DOWN_SCRIPT' when finished." >&2
  exit 1
fi

echo
echo "VERIFY PASSED: all $PASSED checks passed."

# Required end-to-end cycle: stop the stack, keep the volumes, prove compose ps is empty.
echo "Stopping the stack to prove the clean cycle..."
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
for volume in jl-mysql-data jl-redis-data jl-rabbitmq-data; do
  if ! docker volume inspect "$volume" >/dev/null 2>&1; then
    volumes_ok=false
    fail "named volume $volume preserved" "volume missing"
  fi
done
if $volumes_ok; then
  pass "named volumes preserved (jl-mysql-data, jl-redis-data, jl-rabbitmq-data)"
else
  echo "VERIFY FAILED: one or more named volumes are missing after down." >&2
  exit 1
fi

echo
echo "CLEAN EXIT: verified, stopped, compose project empty, volumes preserved."
