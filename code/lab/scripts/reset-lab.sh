#!/usr/bin/env bash
# Reset lab *data* to a repeatable baseline without touching Docker volumes.
#
#   bash code/lab/scripts/reset-lab.sh --yes
#   bash code/lab/scripts/reset-lab.sh --dry-run
#   RESET_BASELINE_STOCK=200 bash code/lab/scripts/reset-lab.sh --yes
#
# Scope (lab data only):
#   - MySQL: truncate order_order; reset inventory_stock for every catalog
#     product to RESET_BASELINE_STOCK (reserved=0). Flyway history and catalog
#     seeds are untouched.
#   - Redis: FLUSHDB on the lab Redis the stack actually runs (see the
#     topology-resolution note below).
#   - RabbitMQ: purge the seckill queue via the management API.
#   - App tier: stop app-1/2/3 before the data reset (so no consumer can write
#     during it), then start them again; the startup seeder repopulates Redis
#     stock from MySQL and runtime toggles/circuit breakers/Caffeine L1 return
#     to their seeds.
#
# Reset-target topology (B0-06): the MySQL/Redis targets are resolved from the
# current stack topology instead of hard-defaulting to the standalone
# containers. Two signals drive it - the declared switch keys MYSQL_MODE /
# REDIS_MODE (the same ones compose injects into the app tier, read from the
# already-sourced .env; RESET_MYSQL_MODE / RESET_REDIS_MODE override them),
# cross-checked against a live docker-inspect probe of the lab containers:
#   standalone : jl-mysql (socket) / jl-redis
#   router     : InnoDB Cluster through MySQL Router (mysql-router:6446), with
#                the first running jl-mysql-<n> as the client container;
#                without a running Router the nodes are probed one by one and
#                the current PRIMARY (read_only=0) is targeted.
#   sentinel   : every running jl-redis-<n> is flushed; verification follows
#                the current master (redis "info replication" role probe).
# An unresolvable or contradictory topology aborts (exit 2) BEFORE any change
# and prints the observed container states. The full resolved target list is
# printed before anything happens (also under --dry-run).
#
# Never runs `docker compose down`, never removes volumes, and never touches a
# container outside the pinned `java-learn-lab` compose project. Every value
# (paths, container/service names, baseline, queue, credentials, timeouts,
# retries) is environment-overridable; credentials come from the lab .env and
# never appear on argv, in logs or in any archive: MySQL receives MYSQL_PWD
# through a 0600 docker-exec --env-file and the RabbitMQ management API basic
# auth is fed to curl through stdin (--config -). Both temp files are removed
# by an EXIT trap.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

DOCKER_DIR="${RESET_DOCKER_DIR:-$ROOT_DIR/code/lab/docker}"
ENV_FILE="${RESET_ENV_FILE:-$DOCKER_DIR/.env}"
COMPOSE_PROJECT="${RESET_COMPOSE_PROJECT:-java-learn-lab}"

MYSQL_SERVICE="${RESET_MYSQL_SERVICE:-mysql}"
REDIS_SERVICE="${RESET_REDIS_SERVICE:-redis}"
RABBITMQ_SERVICE="${RESET_RABBITMQ_SERVICE:-rabbitmq}"
APP_SERVICES_RAW="${RESET_APP_SERVICES:-app-1 app-2 app-3}"

# Container names are only used to address the lab-only datastores directly.
# Their compose project label is checked in the preflight, so a same-named
# container from another project can never be reset by mistake.
MYSQL_CONTAINER="${RESET_MYSQL_CONTAINER:-jl-mysql}"
REDIS_CONTAINER="${RESET_REDIS_CONTAINER:-jl-redis}"
RABBITMQ_CONTAINER="${RESET_RABBITMQ_CONTAINER:-jl-rabbitmq}"

BASELINE_STOCK="${RESET_BASELINE_STOCK:-100}"
RABBITMQ_QUEUE="${RABBITMQ_QUEUE:-seckill.order.queue}"
RABBITMQ_MANAGEMENT_URL="${RABBITMQ_MANAGEMENT_URL:-http://127.0.0.1:15672}"
SECKILL_STOCK_KEY_PREFIX="${RESET_SECKILL_STOCK_KEY_PREFIX:-seckill:stock:}"

# --- Topology-resolution knobs (B0-06; all env-overridable, 工程规约 §1) -----
# Empty RESET_MYSQL_MODE / RESET_REDIS_MODE means "use the declared switch keys
# from the lab .env" (MYSQL_MODE / REDIS_MODE), which is what the app tier is
# configured with; the live container probe then validates it.
RESET_MYSQL_MODE="${RESET_MYSQL_MODE:-}"
RESET_REDIS_MODE="${RESET_REDIS_MODE:-}"
DEFAULT_MYSQL_MODE="${DEFAULT_MYSQL_MODE:-standalone}"
DEFAULT_REDIS_MODE="${DEFAULT_REDIS_MODE:-standalone}"
CONTAINER_PREFIX="${RESET_CONTAINER_PREFIX:-jl-}"
# DB-HA MySQL cluster: node service names (compose.mysql.yml), the Router
# service/host and its read-write port.
MYSQL_HA_NODES_RAW="${RESET_MYSQL_HA_NODES:-mysql-1 mysql-2 mysql-3}"
MYSQL_ROUTER_CONTAINER="${RESET_MYSQL_ROUTER_CONTAINER:-${CONTAINER_PREFIX}mysql-router}"
MYSQL_ROUTER_SERVICE="${RESET_MYSQL_ROUTER_SERVICE:-mysql-router}"
MYSQL_ROUTER_HOST="${RESET_MYSQL_ROUTER_HOST:-mysql-router}"
MYSQL_ROUTER_RW_PORT="${RESET_MYSQL_ROUTER_RW_PORT:-6446}"
MYSQL_NODE_PORT="${RESET_MYSQL_NODE_PORT:-3306}"
# Redis HA (compose.ha.yml) data-node service names and client port.
REDIS_HA_NODES_RAW="${RESET_REDIS_HA_NODES:-redis-1 redis-2 redis-3}"
REDIS_NODE_PORT="${RESET_REDIS_NODE_PORT:-6379}"
# Role probes during resolution (cluster primary / redis master discovery).
TOPOLOGY_PROBE_RETRIES="${RESET_TOPOLOGY_PROBE_RETRIES:-3}"
TOPOLOGY_PROBE_INTERVAL_SECONDS="${RESET_TOPOLOGY_PROBE_INTERVAL_SECONDS:-2}"
MYSQL_CONNECT_TIMEOUT_SECONDS="${RESET_MYSQL_CONNECT_TIMEOUT_SECONDS:-5}"

HEALTH_URL="${RESET_HEALTH_URL:-http://127.0.0.1:8080/health}"
APP_HEALTH_TIMEOUT_SECONDS="${RESET_APP_HEALTH_TIMEOUT_SECONDS:-180}"
APP_HEALTH_INTERVAL_SECONDS="${RESET_APP_HEALTH_INTERVAL_SECONDS:-3}"
VERIFY_RETRIES="${RESET_VERIFY_RETRIES:-30}"
VERIFY_INTERVAL_SECONDS="${RESET_VERIFY_INTERVAL_SECONDS:-1}"
CURL_MAX_TIME="${RESET_CURL_MAX_TIME:-10}"
COMPOSE_TIMEOUT_SECONDS="${RESET_COMPOSE_TIMEOUT_SECONDS:-30}"

YES=false
DRY_RUN=false

usage() {
  cat <<'EOF'
Usage: reset-lab.sh [--yes] [--dry-run]

Reset the java-learn-lab data to a repeatable baseline:
  truncate order_order, reset inventory_stock to RESET_BASELINE_STOCK,
  FLUSHDB the lab Redis, purge the seckill RabbitMQ queue, restart the app
  tier (app-1/2/3) and wait for it to become healthy.

  --yes       Required for a real reset (bench.sh passes it).
  --dry-run   Print the plan and the current state; change nothing.
  -h, --help  Show this help.

Environment (defaults):
  RESET_BASELINE_STOCK=100          RESET_APP_SERVICES="app-1 app-2 app-3"
  RESET_MYSQL_SERVICE=mysql         RESET_MYSQL_CONTAINER=jl-mysql
  RESET_REDIS_SERVICE=redis         RESET_REDIS_CONTAINER=jl-redis
  RESET_RABBITMQ_SERVICE=rabbitmq   RESET_RABBITMQ_CONTAINER=jl-rabbitmq
  RABBITMQ_QUEUE=seckill.order.queue
  RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:15672
  RESET_APP_HEALTH_TIMEOUT_SECONDS=180
  RESET_APP_HEALTH_INTERVAL_SECONDS=3
  RESET_VERIFY_RETRIES=30          RESET_VERIFY_INTERVAL_SECONDS=1
  RESET_COMPOSE_TIMEOUT_SECONDS=30
  RESET_ENV_FILE=<repo>/code/lab/docker/.env
  RESET_COMPOSE_PROJECT=java-learn-lab

Topology resolution (B0-06 - the reset targets follow the running stack):
  MYSQL_MODE / REDIS_MODE from the .env declare the topology (the same switch
  compose injects into the app tier); a live docker-inspect probe validates it.
  router/sentinel modes target the cluster (Router 6446 or per-node), NOT the
  standalone jl-mysql/jl-redis. An unresolvable topology aborts with exit 2
  before any change; the resolved target list is printed first (also dry-run).
  RESET_MYSQL_MODE=                       override MYSQL_MODE for this run
  RESET_REDIS_MODE=                       override REDIS_MODE for this run
  RESET_CONTAINER_PREFIX=jl-
  RESET_MYSQL_HA_NODES="mysql-1 mysql-2 mysql-3"
  RESET_MYSQL_ROUTER_CONTAINER=jl-mysql-router  RESET_MYSQL_ROUTER_SERVICE=mysql-router
  RESET_MYSQL_ROUTER_HOST=mysql-router    RESET_MYSQL_ROUTER_RW_PORT=6446
  RESET_MYSQL_NODE_PORT=3306
  RESET_REDIS_HA_NODES="redis-1 redis-2 redis-3"  RESET_REDIS_NODE_PORT=6379
  RESET_TOPOLOGY_PROBE_RETRIES=3          RESET_TOPOLOGY_PROBE_INTERVAL_SECONDS=2
  RESET_MYSQL_CONNECT_TIMEOUT_SECONDS=5

Credentials (MYSQL_USER/MYSQL_PASSWORD/MYSQL_DATABASE/RABBITMQ_DEFAULT_USER/
RABBITMQ_DEFAULT_PASS) are read from RESET_ENV_FILE. No volume is removed and
no container outside the java-learn-lab compose project is touched.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes|-y)
      YES=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'ERROR: unknown argument "%s".\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! "$BASELINE_STOCK" =~ ^[1-9][0-9]*$ ]]; then
  printf 'ERROR: RESET_BASELINE_STOCK must be a positive integer, got "%s".\n' \
    "$BASELINE_STOCK" >&2
  exit 2
fi
for numeric in APP_HEALTH_TIMEOUT_SECONDS APP_HEALTH_INTERVAL_SECONDS \
  VERIFY_RETRIES VERIFY_INTERVAL_SECONDS CURL_MAX_TIME COMPOSE_TIMEOUT_SECONDS \
  TOPOLOGY_PROBE_RETRIES TOPOLOGY_PROBE_INTERVAL_SECONDS \
  MYSQL_CONNECT_TIMEOUT_SECONDS MYSQL_ROUTER_RW_PORT MYSQL_NODE_PORT \
  REDIS_NODE_PORT; do
  value="${!numeric}"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    printf 'ERROR: %s must be a positive integer, got "%s".\n' "$numeric" "$value" >&2
    exit 2
  fi
done
if [[ ! -f "$ENV_FILE" ]]; then
  printf 'ERROR: %s not found. Start the stack with code/lab/scripts/up.sh first.\n' \
    "$ENV_FILE" >&2
  exit 2
fi

# shellcheck disable=SC1090
source "$ENV_FILE"
: "${MYSQL_USER:?MYSQL_USER missing from $ENV_FILE}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD missing from $ENV_FILE}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE missing from $ENV_FILE}"
: "${RABBITMQ_DEFAULT_USER:?RABBITMQ_DEFAULT_USER missing from $ENV_FILE}"
: "${RABBITMQ_DEFAULT_PASS:?RABBITMQ_DEFAULT_PASS missing from $ENV_FILE}"

read -r -a APP_SERVICES <<<"$APP_SERVICES_RAW"
if (( ${#APP_SERVICES[@]} == 0 )); then
  echo "ERROR: RESET_APP_SERVICES is empty." >&2
  exit 2
fi

# --- Credential transport (never on argv) -----------------------------------
# The MySQL password goes into a 0600 file consumed by `docker exec
# --env-file` (as MYSQL_PWD, so the mysql client never sees it on argv and no
# `-e MYSQL_PWD=...` argument ever appears in the process list). The
# RabbitMQ basic-auth string goes to curl through stdin via `--config -`.
# Both temp files are removed by the EXIT trap below.
MYSQL_PWD_FILE="$(mktemp "${TMPDIR:-/tmp}/jl-reset-mysql-XXXXXX")"
SECRETS_TMP=("$MYSQL_PWD_FILE")
chmod 600 "$MYSQL_PWD_FILE"
# shellcheck disable=SC2016
printf 'MYSQL_PWD=%s\n' "$MYSQL_PASSWORD" > "$MYSQL_PWD_FILE"

host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

# True between "app tier stopped" and "app tier started again": if the script
# dies in that window the EXIT trap restarts the app tier instead of leaving
# the stack down, without masking the original exit code.
APP_RECOVERY_NEEDED=false

cleanup_and_recover() {
  local rc=$?
  local file
  for file in "${SECRETS_TMP[@]}"; do
    rm -f "$file"
  done
  if $APP_RECOVERY_NEEDED; then
    APP_RECOVERY_NEEDED=false
    if (( rc != 0 )); then
      printf 'WARN: reset failed (exit %s) while the app tier was stopped; attempting to restart it.\n' "$rc" >&2
    fi
    if compose start "${APP_SERVICES[@]}" >/dev/null 2>&1; then
      printf 'NOTE: app tier restarted after the failed reset (the data reset itself is NOT complete).\n' >&2
    else
      printf 'ERROR: automatic app-tier restart FAILED. Recover manually:\n  cd %s && docker compose --project-name %s --env-file %s -f compose.base.yml -f compose.dev.yml start %s\n' \
        "$DOCKER_DIR" "$COMPOSE_PROJECT" "$ENV_FILE" "${APP_SERVICES[*]}" >&2
    fi
  fi
}
trap cleanup_and_recover EXIT

compose() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$DOCKER_DIR/compose.base.yml" \
    -f "$DOCKER_DIR/compose.dev.yml" \
    "$@"
}

compose_services="$(compose config --services 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$compose_services" ]]; then
  echo "ERROR: docker compose config --services returned nothing (is Docker running?)." >&2
  exit 2
fi

service_exists() {
  grep -qx "$1" <<<"$compose_services"
}

container_for_service() {
  local service="$1" id
  id="$(compose ps -q "$service" 2>/dev/null | head -n 1 || true)"
  if [[ -z "$id" ]]; then
    return 1
  fi
  docker inspect -f '{{.Name}}' "$id" 2>/dev/null | sed 's#^/##'
}

container_state() {
  docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null || true
}

container_health() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "$1" 2>/dev/null || true
}

container_project() {
  docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$1" 2>/dev/null || true
}

# --- Read-only helpers (shared by preflight probes and the state snapshot) --
urlencode_segment() {
  local value="$1" out="" char index encoded
  for (( index = 0; index < ${#value}; index++ )); do
    char="${value:index:1}"
    case "$char" in
      [a-zA-Z0-9.~_-])
        out+="$char"
        ;;
      *)
        printf -v encoded '%%%02X' "'$char"
        out+="$encoded"
        ;;
    esac
  done
  printf '%s' "$out"
}

# Escapes a value for use inside a double-quoted curl --config string.
curl_config_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "$value"
}

# Runs curl with the RabbitMQ basic credentials supplied on stdin
# (`--config -`), so user:pass never appears in argv or in ps output.
rabbit_curl() {
  printf 'user = %s\n' \
    "$(curl_config_quote "${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}")" |
    curl --config - "$@"
}

# Runs a read-only or write SQL statement; the password travels via the 0600
# MYSQL_PWD env file, only the (non-sensitive) user name is on argv. The host
# and port come from the resolved topology (empty host = container-local socket).
MYSQL_EXEC_CONTAINER="$MYSQL_CONTAINER"
MYSQL_EXEC_HOST=""
MYSQL_EXEC_PORT=""
MYSQL_CONN_ARGS=()

mysql_conn_args() { # rebuild the -h/-P argv tail from the resolved connection
  if [[ -n "$MYSQL_EXEC_HOST" ]]; then
    MYSQL_CONN_ARGS=(-h "$MYSQL_EXEC_HOST" -P "$MYSQL_EXEC_PORT")
  else
    MYSQL_CONN_ARGS=()
  fi
}

mysql_exec() {
  mysql_conn_args
  docker exec --env-file "$(host_path "$MYSQL_PWD_FILE")" "$MYSQL_EXEC_CONTAINER" \
    mysql "${MYSQL_CONN_ARGS[@]}" \
    --connect-timeout="$MYSQL_CONNECT_TIMEOUT_SECONDS" \
    -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B -e "$1"
}

# mysql_query_on <container> <host> <port> <sql>: same credential transport, but
# aimed at an explicit node. Used only by the read-only topology probes.
mysql_query_on() {
  local container="$1" host="$2" port="$3" sql="$4" args=()
  if [[ -n "$host" ]]; then
    args=(-h "$host" -P "$port")
  fi
  docker exec --env-file "$(host_path "$MYSQL_PWD_FILE")" "$container" \
    mysql "${args[@]}" --connect-timeout="$MYSQL_CONNECT_TIMEOUT_SECONDS" \
    -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B -e "$sql" 2>/dev/null \
    | tr -d '\r\n' || true
}

# mysql_query_retry <container> <host> <port> <sql>: bounded probe (工程规约 §1:
# every wait gets a timeout + retry count), so a node mid-rejoin cannot hang
# the resolution.
mysql_query_retry() {
  local container="$1" host="$2" port="$3" sql="$4" attempt out=""
  for attempt in $(seq 1 "$TOPOLOGY_PROBE_RETRIES"); do
    out="$(mysql_query_on "$container" "$host" "$port" "$sql")"
    if [[ -n "$out" ]]; then
      printf '%s' "$out"
      return 0
    fi
    if (( attempt < TOPOLOGY_PROBE_RETRIES )); then
      sleep "$TOPOLOGY_PROBE_INTERVAL_SECONDS"
    fi
  done
  return 1
}

mysql_scalar() {
  mysql_exec "$1" 2>/dev/null | tr -d '\r\n' || true
}

redis_cli_on() { # <container> <redis-cli args...>
  docker exec "$1" redis-cli "${@:2}" 2>/dev/null | tr -d '\r' || true
}

# Redis targets are resolved below (REDIS_TARGETS); this wrapper keeps the
# single-instance call sites working against the first resolved node.
REDIS_TARGETS=("$REDIS_CONTAINER")
REDIS_VERIFY_CONTAINER="$REDIS_CONTAINER"
redis_cli() {
  redis_cli_on "$REDIS_VERIFY_CONTAINER" "$@"
}

# First occurrence only: the queue object is a single JSON line and nested
# message stats must not override the queue-level counters.
queue_field() {
  local body
  body="$(rabbit_curl -sS --max-time "$CURL_MAX_TIME" \
    "${RABBITMQ_MANAGEMENT_URL%/}/api/queues/%2F/$(urlencode_segment "$RABBITMQ_QUEUE")" \
    2>/dev/null || true)"
  grep -o "\"$1\":[0-9][0-9]*" <<<"$body" | head -n 1 | cut -d: -f2
}

# --- Reset-target topology resolution (B0-06) --------------------------------
# The reset targets follow the topology the stack actually runs, never a
# hard-coded standalone default. Inputs: the declared switch keys MYSQL_MODE /
# REDIS_MODE (compose injects the same keys into the app tier; read from the
# already-sourced .env, overridable via RESET_MYSQL_MODE / RESET_REDIS_MODE),
# cross-checked against a live docker-inspect probe of the lab containers.
read -r -a MYSQL_HA_NODE_SERVICES <<<"$MYSQL_HA_NODES_RAW"
read -r -a REDIS_HA_NODE_SERVICES <<<"$REDIS_HA_NODES_RAW"
MYSQL_HA_NODE_CONTAINERS=()
for node in "${MYSQL_HA_NODE_SERVICES[@]}"; do
  MYSQL_HA_NODE_CONTAINERS+=("${CONTAINER_PREFIX}${node}")
done
REDIS_HA_NODE_CONTAINERS=()
for node in "${REDIS_HA_NODE_SERVICES[@]}"; do
  REDIS_HA_NODE_CONTAINERS+=("${CONTAINER_PREFIX}${node}")
done

MYSQL_MODE_DECLARED="${RESET_MYSQL_MODE:-${MYSQL_MODE:-$DEFAULT_MYSQL_MODE}}"
REDIS_MODE_DECLARED="${RESET_REDIS_MODE:-${REDIS_MODE:-$DEFAULT_REDIS_MODE}}"

# lab_container_ok <container>: running AND part of the pinned compose project.
lab_container_ok() {
  [[ "$(container_state "$1")" == "running" ]] || return 1
  [[ "$(container_project "$1")" == "$COMPOSE_PROJECT" ]] || return 1
}

topology_observed=()
observe_container() { # <container> -> record "name state project" for the report
  topology_observed+=("$(printf '%-22s state=%-8s project=%s' \
    "$1" "${2:-missing}" "${3:-<none>}")")
}

# fail_topology aborts BEFORE any destructive step (exit 2) and shows exactly
# what was observed, so "which MySQL/Redis is live right now" is answerable
# from the error output alone.
fail_topology() { # <reason>
  printf 'ERROR: cannot resolve the reset target topology; nothing was changed.\n' >&2
  printf '  reason: %s\n' "$1" >&2
  printf '  declared MYSQL_MODE=%s REDIS_MODE=%s (override with RESET_MYSQL_MODE / RESET_REDIS_MODE)\n' \
    "$MYSQL_MODE_DECLARED" "$REDIS_MODE_DECLARED" >&2
  printf '  observed containers:\n' >&2
  for line in "${topology_observed[@]}"; do
    printf '    %s\n' "$line" >&2
  done
  printf '  hints: start the stack (up.sh / db-ha-up.sh); for the cluster route\n' >&2
  printf '         keep MySQL Router up or the nodes with a PRIMARY; node name\n' >&2
  printf '         patterns are RESET_MYSQL_HA_NODES / RESET_REDIS_HA_NODES.\n' >&2
  exit 2
}

# Collect the observations once; every candidate container is reported, whether
# or not it takes part in the decision (that is the "will I reset the right
# thing" audit trail).
for candidate in "$MYSQL_CONTAINER" "${MYSQL_HA_NODE_CONTAINERS[@]}" "$MYSQL_ROUTER_CONTAINER" \
  "$REDIS_CONTAINER" "${REDIS_HA_NODE_CONTAINERS[@]}"; do
  observe_container "$candidate" "$(container_state "$candidate")" "$(container_project "$candidate")"
done

# MySQL: resolve the topology and the connection (exec container + host/port).
mysql_standalone_ok=false
lab_container_ok "$MYSQL_CONTAINER" && mysql_standalone_ok=true
mysql_router_ok=false
lab_container_ok "$MYSQL_ROUTER_CONTAINER" && mysql_router_ok=true
mysql_nodes_running=()
for node_container in "${MYSQL_HA_NODE_CONTAINERS[@]}"; do
  lab_container_ok "$node_container" && mysql_nodes_running+=("$node_container")
done

MYSQL_MODE_NOTE=""
case "$MYSQL_MODE_DECLARED" in
  standalone)
    if $mysql_standalone_ok; then
      MYSQL_TOPOLOGY="standalone"
    elif (( ${#mysql_nodes_running[@]} > 0 )) || $mysql_router_ok; then
      MYSQL_TOPOLOGY="cluster"
      MYSQL_MODE_NOTE="declared MYSQL_MODE=standalone but $MYSQL_CONTAINER is not running; the DB-HA tier is the live MySQL"
    else
      fail_topology "MYSQL_MODE=standalone but no lab MySQL is running ($MYSQL_CONTAINER and the cluster nodes are all down)"
    fi
    ;;
  router)
    if (( ${#mysql_nodes_running[@]} > 0 )) || $mysql_router_ok; then
      MYSQL_TOPOLOGY="cluster"
    else
      fail_topology "MYSQL_MODE=router but no cluster member is running ($MYSQL_ROUTER_CONTAINER and ${MYSQL_HA_NODE_CONTAINERS[*]} are all down)"
    fi
    ;;
  *)
    fail_topology "unknown MYSQL_MODE value \"$MYSQL_MODE_DECLARED\" (expected standalone|router)"
    ;;
esac

MYSQL_EXEC_HOST=""
MYSQL_EXEC_PORT=""
if [[ "$MYSQL_TOPOLOGY" == "standalone" ]]; then
  MYSQL_EXEC_CONTAINER="$MYSQL_CONTAINER"
  MYSQL_ROUTE_DESC="container-local socket in $MYSQL_CONTAINER (standalone dev MySQL)"
else
  if (( ${#mysql_nodes_running[@]} == 0 )); then
    fail_topology "cluster topology resolved but no jl-mysql-<n> node is running: the mysql client must execute inside a node container (the Router image ships none)"
  fi
  if $mysql_router_ok; then
    # Route through MySQL Router 6446: writes land on the current PRIMARY.
    MYSQL_EXEC_CONTAINER="${mysql_nodes_running[0]}"
    MYSQL_EXEC_HOST="$MYSQL_ROUTER_HOST"
    MYSQL_EXEC_PORT="$MYSQL_ROUTER_RW_PORT"
    MYSQL_ROUTE_DESC="MySQL Router $MYSQL_ROUTER_HOST:$MYSQL_ROUTER_RW_PORT (follows the PRIMARY), client exec in $MYSQL_EXEC_CONTAINER"
  else
    # No Router (--skip-router or Router down): probe each running node and
    # target the current PRIMARY directly; Group Replication propagates the
    # writes to the secondaries.
    MYSQL_EXEC_CONTAINER=""
    for node_container in "${mysql_nodes_running[@]}"; do
      read_only="$(mysql_query_retry "$node_container" 127.0.0.1 "$MYSQL_NODE_PORT" 'SELECT @@read_only;')" || true
      if [[ "$read_only" == "0" ]]; then
        MYSQL_EXEC_CONTAINER="$node_container"
        break
      fi
    done
    if [[ -z "$MYSQL_EXEC_CONTAINER" ]]; then
      fail_topology "MYSQL_MODE=router, MySQL Router is not running and none of ${mysql_nodes_running[*]} reports a writable PRIMARY (@@read_only=0); stabilize the group first (db-cluster-status.sh)"
    fi
    MYSQL_EXEC_HOST="127.0.0.1"
    MYSQL_EXEC_PORT="$MYSQL_NODE_PORT"
    MYSQL_ROUTE_DESC="per-node direct: PRIMARY $MYSQL_EXEC_CONTAINER (Router $MYSQL_ROUTER_CONTAINER not running; GR replicates the writes)"
  fi
fi

# Redis: same treatment for the standalone vs Sentinel data-node topology.
redis_standalone_ok=false
lab_container_ok "$REDIS_CONTAINER" && redis_standalone_ok=true
redis_nodes_running=()
for node_container in "${REDIS_HA_NODE_CONTAINERS[@]}"; do
  lab_container_ok "$node_container" && redis_nodes_running+=("$node_container")
done

REDIS_MODE_NOTE=""
case "$REDIS_MODE_DECLARED" in
  standalone)
    if $redis_standalone_ok; then
      REDIS_TARGETS=("$REDIS_CONTAINER")
    elif (( ${#redis_nodes_running[@]} > 0 )); then
      REDIS_TARGETS=("${redis_nodes_running[@]}")
      REDIS_MODE_NOTE="declared REDIS_MODE=standalone but $REDIS_CONTAINER is not running; flushing the HA data nodes instead"
    else
      fail_topology "REDIS_MODE=standalone but no lab Redis is running ($REDIS_CONTAINER and ${REDIS_HA_NODE_CONTAINERS[*]} are all down)"
    fi
    ;;
  sentinel)
    if (( ${#redis_nodes_running[@]} > 0 )); then
      REDIS_TARGETS=("${redis_nodes_running[@]}")
    else
      fail_topology "REDIS_MODE=sentinel but none of ${REDIS_HA_NODE_CONTAINERS[*]} is running"
    fi
    ;;
  *)
    fail_topology "unknown REDIS_MODE value \"$REDIS_MODE_DECLARED\" (expected standalone|sentinel)"
    ;;
esac

# Verification reads the current master/primary member: probe the role on each
# flush target; fall back to the first target when no probe answers (e.g. a
# standalone instance reports role:master anyway).
REDIS_VERIFY_CONTAINER="${REDIS_TARGETS[0]}"
if (( ${#REDIS_TARGETS[@]} > 1 )); then
  for node_container in "${REDIS_TARGETS[@]}"; do
    role_line="$(redis_cli_on "$node_container" info replication | tr -d '\n' || true)"
    if [[ "$role_line" == *"role:master"* ]]; then
      REDIS_VERIFY_CONTAINER="$node_container"
      break
    fi
  done
fi

# --- Print the resolved targets BEFORE anything else -------------------------
echo "Reset targets (resolved from the current stack topology):"
echo "  declared modes   : MYSQL_MODE=$MYSQL_MODE_DECLARED REDIS_MODE=$REDIS_MODE_DECLARED (source: .env + RESET_* overrides)"
echo "  MySQL topology   : $MYSQL_TOPOLOGY"
echo "  MySQL target     : container=$MYSQL_EXEC_CONTAINER db=$MYSQL_DATABASE route=[$MYSQL_ROUTE_DESC]"
if [[ "$MYSQL_TOPOLOGY" == "cluster" ]]; then
  echo "  MySQL nodes seen : ${mysql_nodes_running[*]:-none} router=$($mysql_router_ok && echo running || echo not-running)"
fi
echo "  Redis topology   : $([[ "${REDIS_TARGETS[0]}" == "$REDIS_CONTAINER" ]] && echo standalone || echo cluster)"
echo "  Redis targets    : ${REDIS_TARGETS[*]} (FLUSHDB each; verify on $REDIS_VERIFY_CONTAINER)"
for note in "$MYSQL_MODE_NOTE" "$REDIS_MODE_NOTE"; do
  [[ -n "$note" ]] && printf 'WARN: topology note: %s\n' "$note" >&2
done
echo

# Preflight collects every problem before any destructive action. A real run
# aborts on the first reported issue; --dry-run reports them as warnings so an
# operator can inspect the plan while the stack is stopped.
preflight_problems=()

# The reset targets themselves: the standalone service names only matter in
# standalone mode; cluster targets are validated by the container probe below
# (compose.mysql.yml / compose.ha.yml are not part of this reset model).
for service in "$RABBITMQ_SERVICE" "${APP_SERVICES[@]}"; do
  if ! service_exists "$service"; then
    preflight_problems+=("compose service \"$service\" is not part of project $COMPOSE_PROJECT")
  fi
done
if [[ "$MYSQL_TOPOLOGY" == "standalone" ]] && ! service_exists "$MYSQL_SERVICE"; then
  preflight_problems+=("compose service \"$MYSQL_SERVICE\" is not part of project $COMPOSE_PROJECT")
fi
if [[ "${REDIS_TARGETS[0]}" == "$REDIS_CONTAINER" ]] && ! service_exists "$REDIS_SERVICE"; then
  preflight_problems+=("compose service \"$REDIS_SERVICE\" is not part of project $COMPOSE_PROJECT")
fi

# Every resolved target container must be running and inside the pinned
# project. Reject an empty label too: a plain (non-compose) container that
# merely shares the name must never be reset.
for container in "$MYSQL_EXEC_CONTAINER" "${REDIS_TARGETS[@]}" "$RABBITMQ_CONTAINER"; do
  state="$(container_state "$container")"
  project="$(container_project "$container")"
  if [[ "$state" != "running" ]]; then
    preflight_problems+=("$container is not running (state=${state:-missing})")
  fi
  if [[ "$project" != "$COMPOSE_PROJECT" ]]; then
    preflight_problems+=("$container compose project label is \"${project:-<missing>}\", expected \"$COMPOSE_PROJECT\"")
  fi
done

app_containers=()
for service in "${APP_SERVICES[@]}"; do
  container="$(container_for_service "$service" || true)"
  if [[ -z "$container" ]]; then
    preflight_problems+=("compose service \"$service\" has no container")
    continue
  fi
  app_containers+=("$container")
  state="$(container_state "$container")"
  project="$(container_project "$container")"
  if [[ "$state" != "running" ]]; then
    preflight_problems+=("$container is not running (state=${state:-missing})")
  fi
  if [[ "$project" != "$COMPOSE_PROJECT" ]]; then
    preflight_problems+=("$container compose project label is \"${project:-<missing>}\", expected \"$COMPOSE_PROJECT\"")
  fi
done

# The management API and the target queue must both be usable before anything
# is stopped: a wrong RABBITMQ_QUEUE has to fail here, not after the truncate.
rabbit_code="$(rabbit_curl -sS -o /dev/null -w '%{http_code}' --max-time "$CURL_MAX_TIME" \
  "${RABBITMQ_MANAGEMENT_URL%/}/api/overview" 2>/dev/null || true)"
if [[ "$rabbit_code" != "200" ]]; then
  preflight_problems+=("RabbitMQ management API returned HTTP ${rabbit_code:-000}")
else
  queue_probe_code="$(rabbit_curl -sS -o /dev/null -w '%{http_code}' --max-time "$CURL_MAX_TIME" \
    "${RABBITMQ_MANAGEMENT_URL%/}/api/queues/%2F/$(urlencode_segment "$RABBITMQ_QUEUE")" 2>/dev/null || true)"
  if [[ "$queue_probe_code" != "200" ]]; then
    preflight_problems+=("RabbitMQ queue \"$RABBITMQ_QUEUE\" not found via management API (HTTP ${queue_probe_code:-000}); check RABBITMQ_QUEUE")
  fi
fi

# Readability probes for the two direct-exec datastores, so a wrong
# credential or dead service is caught before the app tier goes down. The
# MySQL probe now runs against the RESOLVED target (cluster route included).
mysql_probe="$(mysql_scalar 'SELECT 1;')"
if [[ "$mysql_probe" != "1" ]]; then
  preflight_problems+=("cannot run read-only MySQL queries on $MYSQL_EXEC_CONTAINER via [$MYSQL_ROUTE_DESC] (check credentials/database)")
else
  catalog_probe="$(mysql_scalar 'SELECT COUNT(*) FROM catalog_product;')"
  if [[ ! "$catalog_probe" =~ ^[0-9]+$ ]] || (( catalog_probe == 0 )); then
    preflight_problems+=("catalog_product is empty or unreadable (count=${catalog_probe:-?}); refusing to reset")
  fi
fi

for target in "${REDIS_TARGETS[@]}"; do
  redis_ping="$(redis_cli_on "$target" ping | tr -d '\n' || true)"
  if [[ "$redis_ping" != "PONG" ]]; then
    preflight_problems+=("redis-cli ping on $target returned \"${redis_ping:-no response}\", expected PONG")
  fi
done

if (( ${#preflight_problems[@]} > 0 )); then
  if $DRY_RUN; then
    echo "WARN: preflight found issues (dry-run continues):" >&2
    for problem in "${preflight_problems[@]}"; do
      printf '  - %s\n' "$problem" >&2
    done
  else
    echo "ERROR: reset preflight failed; nothing was changed:" >&2
    for problem in "${preflight_problems[@]}"; do
      printf '  - %s\n' "$problem" >&2
    done
    exit 1
  fi
fi

# --- Read-only state snapshot (used by dry-run and the final summary) -------
catalog_count="$(mysql_scalar 'SELECT COUNT(*) FROM catalog_product;')"
inventory_rows="$(mysql_scalar 'SELECT COUNT(*) FROM inventory_stock;')"
inventory_min="$(mysql_scalar 'SELECT MIN(available) FROM inventory_stock;')"
inventory_max="$(mysql_scalar 'SELECT MAX(available) FROM inventory_stock;')"
reserved_sum="$(mysql_scalar 'SELECT COALESCE(SUM(reserved), 0) FROM inventory_stock;')"
order_rows="$(mysql_scalar 'SELECT COUNT(*) FROM order_order;')"
queue_ready="$(queue_field messages_ready)"
queue_unacked="$(queue_field messages_unacknowledged)"
redis_dbsize="$(redis_cli dbsize | tr -d '\n' || true)"

echo "Reset plan (project=$COMPOSE_PROJECT, env=$ENV_FILE)"
echo "  1. stop app tier      : ${APP_SERVICES[*]}"
echo "  2. MySQL              : TRUNCATE order_order; inventory_stock -> available=$BASELINE_STOCK, reserved=0"
echo "                          (topology=$MYSQL_TOPOLOGY, exec in $MYSQL_EXEC_CONTAINER, route=[$MYSQL_ROUTE_DESC])"
echo "  3. Redis              : FLUSHDB on ${REDIS_TARGETS[*]} (verify on $REDIS_VERIFY_CONTAINER)"
echo "  4. RabbitMQ           : purge queue \"$RABBITMQ_QUEUE\""
echo "  5. start app tier     : ${APP_SERVICES[*]} (stock seeder repopulates Redis, toggles reset)"
echo "  6. verify             : inventory min/max=$BASELINE_STOCK, reserved=0, orders=0,"
echo "                          redis ${SECKILL_STOCK_KEY_PREFIX}<id> for every product, queue empty, apps healthy"
echo
echo "Current state: catalog=$catalog_count inventory_rows=$inventory_rows" \
  "available_min=${inventory_min:-?} available_max=${inventory_max:-?}" \
  "reserved_sum=${reserved_sum:-?} orders=${order_rows:-?}" \
  "redis_keys=${redis_dbsize:-?} queue_ready=${queue_ready:-?} queue_unacked=${queue_unacked:-?}"

if $DRY_RUN; then
  echo
  echo "DRY RUN: no data was changed."
  exit 0
fi

if ! $YES; then
  echo
  echo "ERROR: this reset truncates orders and overwrites stock. Re-run with --yes." >&2
  exit 2
fi

start_seconds=$SECONDS
queue_path="/api/queues/%2F/$(urlencode_segment "$RABBITMQ_QUEUE")"

step() {
  printf 'RESET step: %s\n' "$1"
}

# 1. Quiesce the app tier first so no consumer can create an order between the
#    DB truncate and the queue purge. `stop` + `start` is a full restart of the
#    same containers (fresh JVM, toggles, circuit breakers, Caffeine L1).
#    From here until the start below, the EXIT trap restarts the app tier on
#    any failure so a mid-reset error never leaves the stack down.
step "stopping app tier (${APP_SERVICES[*]})"
APP_RECOVERY_NEEDED=true
compose stop --timeout "$COMPOSE_TIMEOUT_SECONDS" "${APP_SERVICES[@]}" >/dev/null

# 2. MySQL: orders and stock only; Flyway history and catalog seeds stay.
step "truncating order_order and resetting inventory_stock to $BASELINE_STOCK"
mysql_reset_sql="
TRUNCATE TABLE order_order;
UPDATE inventory_stock SET available = ${BASELINE_STOCK}, reserved = 0;
INSERT INTO inventory_stock (product_id, available, reserved)
SELECT c.id, ${BASELINE_STOCK}, 0
FROM catalog_product c
WHERE NOT EXISTS (SELECT 1 FROM inventory_stock s WHERE s.product_id = c.id);
"
mysql_exec "$mysql_reset_sql" >/dev/null

# 3. Redis FLUSHDB on every resolved target: the lab instance(s) hold only lab
#    keys (cache, login tokens, seckill idempotency claims, seckill:stock:*).
#    In the cluster topology each data node is flushed explicitly (a detached
#    replica must not keep stale stock keys).
step "flushing Redis DB on ${REDIS_TARGETS[*]}"
for target in "${REDIS_TARGETS[@]}"; do
  redis_flush="$(redis_cli_on "$target" flushdb | tr -d '\n' || true)"
  if [[ "$redis_flush" != "OK" ]]; then
    echo "ERROR: redis-cli flushdb on $target returned \"$redis_flush\"." >&2
    exit 1
  fi
done

# 4. RabbitMQ: delete ready messages; the durable queue and bindings stay.
step "purging RabbitMQ queue \"$RABBITMQ_QUEUE\""
purge_code="$(rabbit_curl -sS -o /dev/null -w '%{http_code}' --max-time "$CURL_MAX_TIME" \
  -X DELETE "${RABBITMQ_MANAGEMENT_URL%/}${queue_path}/contents" 2>/dev/null || true)"
case "$purge_code" in
  204)
    ;;
  404)
    echo "WARN: queue \"$RABBITMQ_QUEUE\" disappeared mid-reset; nothing to purge." >&2
    ;;
  *)
    echo "ERROR: RabbitMQ purge returned HTTP ${purge_code:-000}." >&2
    exit 1
    ;;
esac

# 5. Start the app tier; the stock seeder repopulates Redis from MySQL.
step "starting app tier (${APP_SERVICES[*]})"
compose start "${APP_SERVICES[@]}" >/dev/null
APP_RECOVERY_NEEDED=false

step "waiting for app tier health (timeout ${APP_HEALTH_TIMEOUT_SECONDS}s)"
deadline=$(( SECONDS + APP_HEALTH_TIMEOUT_SECONDS ))
while :; do
  all_healthy=true
  health_line=""
  for container in "${app_containers[@]}"; do
    status="$(container_health "$container")"
    health_line+="${container}=${status:-missing} "
    if [[ "$status" != "healthy" ]]; then
      all_healthy=false
    fi
  done
  if $all_healthy; then
    printf 'RESET step: app tier healthy (%s)\n' "${health_line% }"
    break
  fi
  if (( SECONDS >= deadline )); then
    printf 'ERROR: app tier did not become healthy within %ss: %s\n' \
      "$APP_HEALTH_TIMEOUT_SECONDS" "${health_line% }" >&2
    compose ps >&2 || true
    exit 1
  fi
  sleep "$APP_HEALTH_INTERVAL_SECONDS"
done

nginx_health=""
for attempt in $(seq 1 "$VERIFY_RETRIES"); do
  nginx_health="$(curl -fsS --max-time "$CURL_MAX_TIME" "$HEALTH_URL" 2>/dev/null \
    | tr -d '\r\n' || true)"
  if [[ "$nginx_health" == "ok" ]]; then
    break
  fi
  if (( attempt < VERIFY_RETRIES )); then
    sleep "$VERIFY_INTERVAL_SECONDS"
  fi
done
if [[ "$nginx_health" != "ok" ]]; then
  printf 'ERROR: nginx health endpoint %s returned "%s".\n' "$HEALTH_URL" "$nginx_health" >&2
  exit 1
fi

# 6. Verify the baseline. Redis stock is polled: the seeder runs after the web
#    server is up, so a healthy app does not yet guarantee the keys exist.
step "verifying baseline"
catalog_count="$(mysql_scalar 'SELECT COUNT(*) FROM catalog_product;')"
inventory_rows="$(mysql_scalar 'SELECT COUNT(*) FROM inventory_stock;')"
inventory_min="$(mysql_scalar 'SELECT MIN(available) FROM inventory_stock;')"
inventory_max="$(mysql_scalar 'SELECT MAX(available) FROM inventory_stock;')"
reserved_sum="$(mysql_scalar 'SELECT COALESCE(SUM(reserved), 0) FROM inventory_stock;')"
order_rows="$(mysql_scalar 'SELECT COUNT(*) FROM order_order;')"
product_ids="$(mysql_scalar 'SELECT GROUP_CONCAT(id ORDER BY id) FROM catalog_product;')"

verify_problems=()
if [[ "$inventory_rows" != "$catalog_count" || "$inventory_rows" == "0" ]]; then
  verify_problems+=("inventory rows=$inventory_rows, expected one per catalog product ($catalog_count)")
fi
if [[ "$inventory_min" != "$BASELINE_STOCK" || "$inventory_max" != "$BASELINE_STOCK" ]]; then
  verify_problems+=("inventory available min=$inventory_min max=$inventory_max, expected $BASELINE_STOCK")
fi
if [[ "$reserved_sum" != "0" ]]; then
  verify_problems+=("inventory reserved sum=$reserved_sum, expected 0")
fi
if [[ "$order_rows" != "0" ]]; then
  verify_problems+=("order_order rows=$order_rows, expected 0")
fi

seeded_count=0
redis_values=""
product_id_array=()
if [[ -n "$product_ids" ]]; then
  IFS=',' read -r -a product_id_array <<<"$product_ids"
  redis_keys=()
  for product_id in "${product_id_array[@]}"; do
    redis_keys+=("${SECKILL_STOCK_KEY_PREFIX}${product_id}")
  done
  for attempt in $(seq 1 "$VERIFY_RETRIES"); do
    redis_values="$(redis_cli --raw mget "${redis_keys[@]}" || true)"
    seeded_count=0
    while IFS= read -r value; do
      if [[ "$value" == "$BASELINE_STOCK" ]]; then
        seeded_count=$((seeded_count + 1))
      fi
    done <<<"$redis_values"
    if (( seeded_count == ${#redis_keys[@]} )); then
      break
    fi
    if (( attempt < VERIFY_RETRIES )); then
      sleep "$VERIFY_INTERVAL_SECONDS"
    fi
  done
  if (( seeded_count != ${#redis_keys[@]} )); then
    verify_problems+=("redis has $seeded_count/${#redis_keys[@]} ${SECKILL_STOCK_KEY_PREFIX}<id> keys at $BASELINE_STOCK")
  fi
else
  verify_problems+=("catalog_product returned no product ids")
fi

queue_ready="$(queue_field messages_ready)"
queue_unacked="$(queue_field messages_unacknowledged)"
if [[ "$queue_ready" != "0" || "$queue_unacked" != "0" ]]; then
  verify_problems+=("queue \"$RABBITMQ_QUEUE\" ready=$queue_ready unacked=$queue_unacked, expected 0/0")
fi

for container in "${app_containers[@]}"; do
  status="$(container_health "$container")"
  if [[ "$status" != "healthy" ]]; then
    verify_problems+=("$container health=$status, expected healthy")
  fi
done

elapsed=$(( SECONDS - start_seconds ))
echo
echo "RESET SUMMARY"
echo "  project        : $COMPOSE_PROJECT"
echo "  mysql target   : $MYSQL_EXEC_CONTAINER ($MYSQL_TOPOLOGY) via [$MYSQL_ROUTE_DESC]"
echo "  redis targets  : ${REDIS_TARGETS[*]} (verified on $REDIS_VERIFY_CONTAINER)"
echo "  baseline stock : $BASELINE_STOCK"
echo "  catalog        : $catalog_count products"
echo "  inventory      : rows=$inventory_rows min=$inventory_min max=$inventory_max reserved_sum=$reserved_sum"
echo "  orders         : $order_rows"
echo "  redis stock    : $seeded_count/${#product_id_array[@]} keys at $BASELINE_STOCK"
echo "  queue          : $RABBITMQ_QUEUE ready=$queue_ready unacked=$queue_unacked"
echo "  app health     : ${health_line% }"
echo "  nginx health   : $nginx_health"
echo "  elapsed        : ${elapsed}s"

if (( ${#verify_problems[@]} > 0 )); then
  echo
  echo "RESET FAILED: baseline verification failed:" >&2
  for problem in "${verify_problems[@]}"; do
    printf '  - %s\n' "$problem" >&2
  done
  exit 1
fi

echo
echo "RESET PASSED: baseline restored (stock=$BASELINE_STOCK, orders=0, queue empty, apps healthy)."
