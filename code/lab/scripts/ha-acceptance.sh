#!/usr/bin/env bash
set -euo pipefail

# Repeatable HA acceptance drill for the local teaching lab.
#
# Two modes, matching the two manually documented drills:
#   sentinel  step-24d: kill the current Redis Sentinel master, watch the
#             promotion, keep Nginx product reads alive, then let the TTL bring
#             the old master back as a replica.
#   mysql     step-31: kill the current InnoDB Cluster primary, watch MySQL
#             Router 6446 move to the new primary (new server_id, READ_WRITE),
#             keep Nginx product reads alive, then let the TTL bring the old
#             primary back as an ONLINE SECONDARY.
#
# Every run ends with POST /chaos/stop-all (also on failure), asserts that no
# active action remains, and refuses to leave the drill target stopped.
#
# SAFETY
#   * Only containers whose whole name matches the controller's datastore
#     allow-list are eligible. The target is resolved from the live topology,
#     never guessed: the Sentinel/InnoDB primary is the only node killed.
#   * A pre-existing active chaos action aborts the run before anything is
#     killed (stop-all would otherwise revert somebody else's fault).
#   * No credential is printed. .env is parsed key-by-key, never sourced.
#
# CONFIGURATION
#   Process environment > code/lab/docker/.env > the defaults below.
#   Every TWEAKABLE value is a HA_ACCEPT_* variable documented in
#   doc/process/step-45-ha-acceptance-script.md and echoed by --help.
#   Nothing operational is hardcoded outside this block.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
STATUS_SCRIPT="$SCRIPT_DIR/db-cluster-status.sh"

# ---------------------------------------------------------------------------
# Configuration (environment > .env > default). Keep this block at the top.
# ---------------------------------------------------------------------------

# Read one key from .env without sourcing it. Only the requested key is read;
# credentials never enter this process' environment through this helper.
dotenv_value() {
  local key="$1" line value
  [[ -r "$ENV_FILE" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" =~ ^[[:space:]]*${key}[[:space:]]*=(.*)$ ]]; then
      value="${BASH_REMATCH[1]}"
      value="${value%\"}"; value="${value#\"}"
      value="${value%\'}"; value="${value#\'}"
      printf '%s' "$value"
      return 0
    fi
  done < "$ENV_FILE"
  return 0
}

# environment > .env > fallback, the precedence used by the other lab scripts.
env_default() { # <key> <fallback>
  local key="$1" fallback="$2" value=""
  if [[ -n "${!key:-}" ]]; then
    printf '%s' "${!key}"
    return 0
  fi
  value="$(dotenv_value "$key")"
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
    return 0
  fi
  printf '%s' "$fallback"
}

ENV_FILE="${HA_ACCEPT_ENV_FILE:-$DOCKER_DIR/.env}"
COMPOSE_PROJECT="$(env_default HA_ACCEPT_COMPOSE_PROJECT java-learn-lab)"
CONTROLLER_URL="$(env_default HA_ACCEPT_CONTROLLER_URL http://127.0.0.1:8080/lab-api)"
CONTROLLER_URL="${CONTROLLER_URL%/}"
NGINX_URL="$(env_default HA_ACCEPT_NGINX_URL http://127.0.0.1:8080)"
NGINX_URL="${NGINX_URL%/}"
PRODUCT_PATH="$(env_default HA_ACCEPT_PRODUCT_PATH /api/products/1)"
# Token precedence: HA_ACCEPT_LAB_TOKEN (one-run override) > LAB_TOKEN (env) >
# LAB_TOKEN in .env > lab-token. It is never printed.
LAB_TOKEN="${HA_ACCEPT_LAB_TOKEN:-$(env_default LAB_TOKEN lab-token)}"

CURL_TIMEOUT_SECONDS="$(env_default HA_ACCEPT_CURL_TIMEOUT_SECONDS 5)"
# The kill POST is synchronous: the controller returns only after
# gateway.stop(target, chaos.stop-grace-seconds) has run (default 10s). A MySQL
# node needs seconds to shut InnoDB down, so a 5s budget answers 000 while the
# action is already applied. This budget must cover the stop grace, not the
# probe latency; it is used for the kill request only.
KILL_TIMEOUT_SECONDS="$(env_default HA_ACCEPT_KILL_TIMEOUT_SECONDS 30)"
EXEC_TIMEOUT_SECONDS="$(env_default HA_ACCEPT_EXEC_TIMEOUT_SECONDS 5)"
POLL_INTERVAL_SECONDS="$(env_default HA_ACCEPT_POLL_INTERVAL_SECONDS 2)"
HTTP_RECOVERY_THRESHOLD_SECONDS="$(env_default HA_ACCEPT_HTTP_RECOVERY_THRESHOLD_SECONDS 15)"
HTTP_RECOVERY_BUDGET_SECONDS="$(env_default HA_ACCEPT_HTTP_RECOVERY_BUDGET_SECONDS 30)"
STOP_BUDGET_SECONDS="$(env_default HA_ACCEPT_STOP_BUDGET_SECONDS 20)"
STOP_ALL_BUDGET_SECONDS="$(env_default HA_ACCEPT_STOP_ALL_BUDGET_SECONDS 30)"
APP_CONTAINERS="$(env_default HA_ACCEPT_APP_CONTAINERS 'jl-app-1 jl-app-2 jl-app-3')"
NGINX_CONTAINER="$(env_default HA_ACCEPT_NGINX_CONTAINER jl-nginx)"
CONTROLLER_CONTAINER="$(env_default HA_ACCEPT_CONTROLLER_CONTAINER jl-chaos-controller)"

# Redis Sentinel drill (step-24d measured ~7.6s promotion, +9.1s first 200).
SENTINEL_DATA_CONTAINERS="$(env_default HA_ACCEPT_SENTINEL_DATA_CONTAINERS 'jl-redis-1 jl-redis-2 jl-redis-3')"
SENTINEL_CONTAINERS="$(env_default HA_ACCEPT_SENTINEL_CONTAINERS 'jl-sentinel-1 jl-sentinel-2 jl-sentinel-3')"
SENTINEL_MASTER_NAME="$(env_default HA_ACCEPT_SENTINEL_MASTER_NAME "$(env_default REDIS_HA_MASTER_NAME mymaster)")"
SENTINEL_NODE_PORT="$(env_default HA_ACCEPT_SENTINEL_NODE_PORT "$(env_default REDIS_HA_NODE_PORT 6379)")"
SENTINEL_PORT="$(env_default HA_ACCEPT_SENTINEL_PORT "$(env_default REDIS_HA_SENTINEL_PORT 26379)")"
# Password is used inside the container via REDISCLI_AUTH and never printed.
SENTINEL_PASSWORD="$(env_default HA_ACCEPT_SENTINEL_PASSWORD "$(env_default REDIS_HA_PASSWORD '')")"
# auto = kill whichever data node currently reports role=master. An explicit
# name is honoured only when it is that same node (killing a replica is not the
# drill). step-24d warns that the master drifts, so auto is the repeatable mode.
SENTINEL_TARGET="$(env_default HA_ACCEPT_SENTINEL_TARGET auto)"
SENTINEL_TTL_SECONDS="$(env_default HA_ACCEPT_SENTINEL_TTL_SECONDS 45)"
SENTINEL_PROMOTION_BUDGET_SECONDS="$(env_default HA_ACCEPT_SENTINEL_PROMOTION_BUDGET_SECONDS 60)"
SENTINEL_REJOIN_BUDGET_SECONDS="$(env_default HA_ACCEPT_SENTINEL_REJOIN_BUDGET_SECONDS 120)"
SENTINEL_REQUIRE_AGREEMENT="$(env_default HA_ACCEPT_SENTINEL_REQUIRE_AGREEMENT true)"
# A Sentinel drill only exercises the app path when the app tier was recreated
# under the ha profile (REDIS_MODE=sentinel). The check can be downgraded to a
# warning for a topology-only run.
SENTINEL_REQUIRE_SENTINEL_MODE="$(env_default HA_ACCEPT_SENTINEL_REQUIRE_SENTINEL_MODE true)"

# MySQL Router drill (step-31 measured 6446 -> server_id=2 READ_WRITE, 70/70
# reads 200, TTL 45s restart, old node back as ONLINE SECONDARY).
MYSQL_NODES="$(env_default HA_ACCEPT_MYSQL_NODES 'jl-mysql-1 jl-mysql-2 jl-mysql-3')"
MYSQL_ROUTER_CONTAINER="$(env_default HA_ACCEPT_MYSQL_ROUTER_CONTAINER jl-mysql-router)"
MYSQL_ROUTER_HOST="$(env_default HA_ACCEPT_MYSQL_ROUTER_HOST mysql-router)"
MYSQL_ROUTER_RW_PORT="$(env_default HA_ACCEPT_MYSQL_ROUTER_RW_PORT "$(env_default MYSQL_HA_ROUTER_RW_PORT 6446)")"
MYSQL_ADMIN_USER="$(env_default HA_ACCEPT_MYSQL_ADMIN_USER "$(env_default MYSQL_HA_ADMIN_USER root)")"
# Name of the env var INSIDE the node container that carries the admin password.
# The value is expanded in-container; it never reaches this process or argv.
MYSQL_PASSWORD_ENV="$(env_default HA_ACCEPT_MYSQL_PASSWORD_ENV MYSQL_ROOT_PASSWORD)"
MYSQL_BIN="$(env_default HA_ACCEPT_MYSQL_BIN "$(env_default MYSQL_HA_MYSQL_BIN mysql)")"
MYSQL_CONNECT_TIMEOUT_SECONDS="$(env_default HA_ACCEPT_MYSQL_CONNECT_TIMEOUT_SECONDS 5)"
# auto = pick an ONLINE node that is NOT the drill target (the Router probe must
# survive the kill). An explicit container name is rejected when it is the
# target, because the probe would die with the node it is measuring.
MYSQL_PROBE_CONTAINER="$(env_default HA_ACCEPT_MYSQL_PROBE_CONTAINER auto)"
# auto = kill the current cluster PRIMARY. An explicit name is honoured only when
# db-cluster-status.sh reports it as PRIMARY.
MYSQL_TARGET="$(env_default HA_ACCEPT_MYSQL_TARGET auto)"
MYSQL_TTL_SECONDS="$(env_default HA_ACCEPT_MYSQL_TTL_SECONDS 45)"
MYSQL_PROMOTION_BUDGET_SECONDS="$(env_default HA_ACCEPT_MYSQL_PROMOTION_BUDGET_SECONDS 90)"
MYSQL_REJOIN_BUDGET_SECONDS="$(env_default HA_ACCEPT_MYSQL_REJOIN_BUDGET_SECONDS 180)"
MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS="$(env_default HA_ACCEPT_MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS 10)"
# A Router drill is only an app-path acceptance when MYSQL_MODE=router; set to
# false for a topology-only run (then the Nginx assertion proves only that the
# app tier stayed up, not that its reads traversed the Router).
MYSQL_REQUIRE_ROUTER_MODE="$(env_default HA_ACCEPT_MYSQL_REQUIRE_ROUTER_MODE true)"
CLEANUP_CLUSTER_BUDGET_SECONDS="$(env_default HA_ACCEPT_CLEANUP_CLUSTER_BUDGET_SECONDS 60)"

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

MODE=""
DRY_RUN=false

usage() {
  cat <<'EOF'
Usage: ha-acceptance.sh <sentinel|mysql> [--dry-run] [-h|--help]

Runs one HA drill end-to-end and asserts every stage:
  preflight -> chaos kill (TTL) -> topology promotion -> Nginx 200 -> TTL
  revert -> old node rejoins -> stop-all cleanup -> N PASS / M FAIL summary.

Modes
  sentinel   Redis Sentinel: kills the current mymaster node, expects a new
             master address, <=15s Nginx recovery, then the old node back as
             role=slave with master_link_status=up (step-24d).
  mysql      MySQL InnoDB Cluster + Router: kills the current PRIMARY, expects
             Router 6446 to answer with a different server_id in READ_WRITE,
             <=15s Nginx recovery, then the old node back as ONLINE SECONDARY
             and db-cluster-status.sh back to RESULT: ok (step-31).

Options
  --dry-run  Print the resolved plan and the compose render check, then stop.
             No controller call, no container command, no state change. Needs
             no Docker beyond `docker compose config`.
  -h, --help Show this help.

Exit codes
  0  every assertion passed (or a dry run completed)
  1  at least one assertion failed; cleanup was still attempted
  2  usage / configuration error (nothing was touched)

Configuration precedence: process environment > code/lab/docker/.env > the
defaults in the script. Useful overrides (all optional):
  HA_ACCEPT_CONTROLLER_URL, HA_ACCEPT_NGINX_URL, HA_ACCEPT_PRODUCT_PATH
  HA_ACCEPT_LAB_TOKEN, HA_ACCEPT_CURL_TIMEOUT_SECONDS, HA_ACCEPT_KILL_TIMEOUT_SECONDS
  HA_ACCEPT_SENTINEL_TTL_SECONDS, HA_ACCEPT_SENTINEL_PROMOTION_BUDGET_SECONDS
  HA_ACCEPT_SENTINEL_REJOIN_BUDGET_SECONDS, HA_ACCEPT_SENTINEL_TARGET
  HA_ACCEPT_MYSQL_TTL_SECONDS, HA_ACCEPT_MYSQL_PROMOTION_BUDGET_SECONDS
  HA_ACCEPT_MYSQL_REJOIN_BUDGET_SECONDS, HA_ACCEPT_MYSQL_TARGET
  HA_ACCEPT_MYSQL_PROBE_CONTAINER, HA_ACCEPT_MYSQL_REQUIRE_ROUTER_MODE
  HA_ACCEPT_HTTP_RECOVERY_THRESHOLD_SECONDS, HA_ACCEPT_POLL_INTERVAL_SECONDS
Full table: doc/process/step-45-ha-acceptance-script.md.

Safety notes
  * The target is the live master/primary, not a hardcoded name. An explicit
    HA_ACCEPT_*_TARGET is accepted only when it is that node.
  * The target must match the controller's datastore allow-list. A mismatch
    surfaces the controller's 403 body and stops before any fault is applied.
  * The script refuses to start when a chaos action is already active, and
    always sends POST /chaos/stop-all at the end (including on failure).
  * The MySQL controller probe (CHAOS_MYSQL_CLUSTER_CONTAINER, default
    jl-mysql-1) can sit on the node this drill kills. The drill still asserts
    Router 6446, but the mysql_cluster_* Prometheus series freeze. Point that
    probe at a non-target node (e.g. jl-mysql-3) in the controller environment
    before the drill; see step-45 for the compose wiring caveat.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    sentinel|mysql)
      if [[ -n "$MODE" && "$MODE" != "$1" ]]; then
        echo "ERROR: only one mode may be given (got '$MODE' and '$1')." >&2
        exit 2
      fi
      MODE="$1"
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
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "ERROR: mode is required (sentinel|mysql)." >&2
  usage >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------

require_uint() { # <label> <value>
  if [[ ! "$2" =~ ^[0-9]+$ ]]; then
    printf 'ERROR: %s must be a non-negative integer, got "%s".\n' "$1" "$2" >&2
    exit 2
  fi
}

require_positive_uint() { # <label> <value>
  if [[ ! "$2" =~ ^[1-9][0-9]*$ ]]; then
    printf 'ERROR: %s must be a positive integer, got "%s".\n' "$1" "$2" >&2
    exit 2
  fi
}

require_bool() { # <label> <value>
  case "$2" in
    true|false) ;;
    *)
      printf 'ERROR: %s must be true or false, got "%s".\n' "$1" "$2" >&2
      exit 2
      ;;
  esac
}

require_nonempty() { # <label> <value>
  if [[ -z "$2" ]]; then
    printf 'ERROR: %s must not be empty.\n' "$1" >&2
    exit 2
  fi
}

require_nonempty HA_ACCEPT_CONTROLLER_URL "$CONTROLLER_URL"
require_nonempty HA_ACCEPT_NGINX_URL "$NGINX_URL"
require_nonempty HA_ACCEPT_PRODUCT_PATH "$PRODUCT_PATH"
require_nonempty HA_ACCEPT_APP_CONTAINERS "$APP_CONTAINERS"
require_nonempty HA_ACCEPT_NGINX_CONTAINER "$NGINX_CONTAINER"
require_nonempty HA_ACCEPT_CONTROLLER_CONTAINER "$CONTROLLER_CONTAINER"
require_nonempty HA_ACCEPT_SENTINEL_DATA_CONTAINERS "$SENTINEL_DATA_CONTAINERS"
require_nonempty HA_ACCEPT_SENTINEL_CONTAINERS "$SENTINEL_CONTAINERS"
require_nonempty HA_ACCEPT_SENTINEL_MASTER_NAME "$SENTINEL_MASTER_NAME"
require_nonempty HA_ACCEPT_SENTINEL_TARGET "$SENTINEL_TARGET"
require_nonempty HA_ACCEPT_MYSQL_NODES "$MYSQL_NODES"
require_nonempty HA_ACCEPT_MYSQL_ROUTER_CONTAINER "$MYSQL_ROUTER_CONTAINER"
require_nonempty HA_ACCEPT_MYSQL_ROUTER_HOST "$MYSQL_ROUTER_HOST"
require_nonempty HA_ACCEPT_MYSQL_ADMIN_USER "$MYSQL_ADMIN_USER"
require_nonempty HA_ACCEPT_MYSQL_PASSWORD_ENV "$MYSQL_PASSWORD_ENV"
require_nonempty HA_ACCEPT_MYSQL_BIN "$MYSQL_BIN"
require_nonempty HA_ACCEPT_MYSQL_PROBE_CONTAINER "$MYSQL_PROBE_CONTAINER"
require_nonempty HA_ACCEPT_MYSQL_TARGET "$MYSQL_TARGET"
if [[ "$PRODUCT_PATH" != /* ]]; then
  printf 'ERROR: HA_ACCEPT_PRODUCT_PATH must start with "/", got "%s".\n' "$PRODUCT_PATH" >&2
  exit 2
fi

require_positive_uint HA_ACCEPT_CURL_TIMEOUT_SECONDS "$CURL_TIMEOUT_SECONDS"
require_positive_uint HA_ACCEPT_KILL_TIMEOUT_SECONDS "$KILL_TIMEOUT_SECONDS"
require_positive_uint HA_ACCEPT_EXEC_TIMEOUT_SECONDS "$EXEC_TIMEOUT_SECONDS"
require_positive_uint HA_ACCEPT_POLL_INTERVAL_SECONDS "$POLL_INTERVAL_SECONDS"
require_positive_uint HA_ACCEPT_HTTP_RECOVERY_THRESHOLD_SECONDS "$HTTP_RECOVERY_THRESHOLD_SECONDS"
require_positive_uint HA_ACCEPT_HTTP_RECOVERY_BUDGET_SECONDS "$HTTP_RECOVERY_BUDGET_SECONDS"
require_positive_uint HA_ACCEPT_STOP_BUDGET_SECONDS "$STOP_BUDGET_SECONDS"
require_positive_uint HA_ACCEPT_STOP_ALL_BUDGET_SECONDS "$STOP_ALL_BUDGET_SECONDS"
require_positive_uint HA_ACCEPT_SENTINEL_NODE_PORT "$SENTINEL_NODE_PORT"
require_positive_uint HA_ACCEPT_SENTINEL_PORT "$SENTINEL_PORT"
require_positive_uint HA_ACCEPT_SENTINEL_TTL_SECONDS "$SENTINEL_TTL_SECONDS"
require_positive_uint HA_ACCEPT_SENTINEL_PROMOTION_BUDGET_SECONDS "$SENTINEL_PROMOTION_BUDGET_SECONDS"
require_positive_uint HA_ACCEPT_SENTINEL_REJOIN_BUDGET_SECONDS "$SENTINEL_REJOIN_BUDGET_SECONDS"
require_bool HA_ACCEPT_SENTINEL_REQUIRE_AGREEMENT "$SENTINEL_REQUIRE_AGREEMENT"
require_bool HA_ACCEPT_SENTINEL_REQUIRE_SENTINEL_MODE "$SENTINEL_REQUIRE_SENTINEL_MODE"
require_positive_uint HA_ACCEPT_MYSQL_ROUTER_RW_PORT "$MYSQL_ROUTER_RW_PORT"
require_positive_uint HA_ACCEPT_MYSQL_CONNECT_TIMEOUT_SECONDS "$MYSQL_CONNECT_TIMEOUT_SECONDS"
require_positive_uint HA_ACCEPT_MYSQL_TTL_SECONDS "$MYSQL_TTL_SECONDS"
require_positive_uint HA_ACCEPT_MYSQL_PROMOTION_BUDGET_SECONDS "$MYSQL_PROMOTION_BUDGET_SECONDS"
require_positive_uint HA_ACCEPT_MYSQL_REJOIN_BUDGET_SECONDS "$MYSQL_REJOIN_BUDGET_SECONDS"
require_positive_uint HA_ACCEPT_MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS "$MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS"
require_positive_uint HA_ACCEPT_CLEANUP_CLUSTER_BUDGET_SECONDS "$CLEANUP_CLUSTER_BUDGET_SECONDS"
require_bool HA_ACCEPT_MYSQL_REQUIRE_ROUTER_MODE "$MYSQL_REQUIRE_ROUTER_MODE"

if [[ ! -x "$STATUS_SCRIPT" && ! -f "$STATUS_SCRIPT" ]]; then
  printf 'ERROR: %s not found; it is required for the mysql preflight.\n' "$STATUS_SCRIPT" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# State and output
# ---------------------------------------------------------------------------

PASSED=0
FAILED=0
CLEANUP_ARMED=false
TARGET_CONTAINER=""
MODE_LABEL="$MODE"
NEW_MASTER_CONTAINER=""
NEW_MASTER_ADDRESS=""
NEW_MYSQL_SERVER_ID=""
PROMOTION_MS=""
HTTP_RECOVERY_MS=""
TTL_REVERT_MS=""
REJOIN_MS=""
ROUTER_SWITCH_MS=""

pass() {
  printf 'PASS %s' "$1"
  if [[ -n "${2:-}" ]]; then printf ' (%s)' "$2"; fi
  printf '\n'
  PASSED=$((PASSED + 1))
}

fail() {
  printf 'FAIL %s' "$1"
  if [[ -n "${2:-}" ]]; then printf ' (%s)' "$2"; fi
  printf '\n'
  FAILED=$((FAILED + 1))
}

skip() {
  printf 'SKIP %s' "$1"
  if [[ -n "${2:-}" ]]; then printf ' (%s)' "$2"; fi
  printf '\n'
}

warn() {
  printf 'WARN %s' "$1"
  if [[ -n "${2:-}" ]]; then printf ' (%s)' "$2"; fi
  printf '\n'
}

now_ms() {
  local value
  if value="$(date +%s%3N 2>/dev/null)" && [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%s' "$value"
  else
    printf '%s' "$(( $(date +%s) * 1000 ))"
  fi
}

fmt_ms() { # <ms> -> "12.345s"
  local ms="${1:-0}"
  printf '%d.%03ds' "$(( ms / 1000 ))" "$(( ms % 1000 ))"
}

print_summary() {
  printf '\n== Summary ==\n'
  printf '%d PASS / %d FAIL\n' "$PASSED" "$FAILED"
  if [[ -n "$PROMOTION_MS" ]]; then
    printf 'Promotion: %s after kill\n' "$(fmt_ms "$PROMOTION_MS")"
  fi
  if [[ -n "$ROUTER_SWITCH_MS" ]]; then
    printf 'Router 6446 switch: %s after kill\n' "$(fmt_ms "$ROUTER_SWITCH_MS")"
  fi
  if [[ -n "$HTTP_RECOVERY_MS" ]]; then
    printf 'Nginx product 200: %s after kill (threshold %ss)\n' \
      "$(fmt_ms "$HTTP_RECOVERY_MS")" "$HTTP_RECOVERY_THRESHOLD_SECONDS"
  fi
  if [[ -n "$TTL_REVERT_MS" ]]; then
    printf 'TTL restart: %s after kill\n' "$(fmt_ms "$TTL_REVERT_MS")"
  fi
  if [[ -n "$REJOIN_MS" ]]; then
    printf 'Old node rejoined: %s after kill\n' "$(fmt_ms "$REJOIN_MS")"
  fi
  if [[ "$DRY_RUN" == true ]]; then
    printf 'Dry run: no lab state was changed.\n'
  fi
}

# ---------------------------------------------------------------------------
# Generic helpers
# ---------------------------------------------------------------------------

container_state() { # -> "running healthy" | "exited no-healthcheck" | "missing"
  local out=""
  if out="$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$1" 2>/dev/null | tr -d '\r')"; then
    printf '%s' "$out"
  else
    printf 'missing'
  fi
}

container_running() {
  [[ "$(container_state "$1")" == running* ]]
}

container_env() { # <container> <key> -> value or empty (only the requested key)
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | tr -d '\r' \
    | awk -F= -v k="$2" '$1 == k { sub(/^[^=]*=/, ""); print; exit }'
}

container_ipv4s() {
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$1" 2>/dev/null \
    | tr -d '\r'
}

address_matches_container() { # <ip:port> <container>
  local address="$1" container="$2" ip="${1%:*}" port="${1##*:}" ips
  [[ "$port" == "$SENTINEL_NODE_PORT" ]] || return 1
  ips="$(container_ipv4s "$container")"
  [[ " $ips " == *" $ip "* ]]
}

word_in_list() { # <word> <space-separated list>
  [[ " $2 " == *" $1 "* ]]
}

http_call() { # <method> <url> [json-body] -> HTTP_CODE / HTTP_BODY
  local method="$1" url="$2" data="${3:-}"
  local -a args=(--silent --show-error --max-time "$CURL_TIMEOUT_SECONDS"
    -X "$method" -H 'Accept: application/json' -w $'\n%{http_code}')
  if [[ -n "$data" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$data")
  fi
  local response
  response="$(curl "${args[@]}" "$url" 2>&1 || true)"
  if [[ "$response" == *$'\n'* ]]; then
    HTTP_CODE="${response##*$'\n'}"
    HTTP_BODY="${response%$'\n'*}"
  else
    HTTP_CODE="000"
    HTTP_BODY="$response"
  fi
}

controller_call() { # <method> <path> [json-body] [timeout-seconds] -> HTTP_CODE / HTTP_BODY
  local method="$1" path="$2" data="${3:-}" timeout="${4:-$CURL_TIMEOUT_SECONDS}"
  local -a args=(--silent --show-error --max-time "$timeout"
    -X "$method" -H 'Accept: application/json' -H "X-Lab-Token: $LAB_TOKEN"
    -w $'\n%{http_code}')
  if [[ -n "$data" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$data")
  fi
  local response
  response="$(curl "${args[@]}" "${CONTROLLER_URL}${path}" 2>&1 || true)"
  if [[ "$response" == *$'\n'* ]]; then
    HTTP_CODE="${response##*$'\n'}"
    HTTP_BODY="${response%$'\n'*}"
  else
    HTTP_CODE="000"
    HTTP_BODY="$response"
  fi
}

json_string() { # <field> <body>
  sed -n "s/.*\"${1}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" <<<"$2" | head -n 1
}

json_number() { # <field> <body>
  sed -n "s/.*\"${1}\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" <<<"$2" | head -n 1
}

body_snippet() { printf '%s' "$1" | tr -d '\r' | head -c 300; }

compose_config_services() {
  local -a files=(-f "$DOCKER_DIR/compose.base.yml" -f "$DOCKER_DIR/compose.dev.yml")
  local profile="" env_file="" err_file="" services=""
  case "$MODE" in
    sentinel)
      files+=(-f "$DOCKER_DIR/compose.ha.yml")
      profile="ha"
      ;;
    mysql)
      files+=(-f "$DOCKER_DIR/compose.mysql.yml")
      profile="db-ha"
      ;;
  esac

  if [[ -f "$ENV_FILE" ]]; then
    env_file="$ENV_FILE"
  elif [[ "$DRY_RUN" == true && -f "$DOCKER_DIR/.env.example" ]]; then
    # Dry runs must work before .env exists (fresh clone / review worktree);
    # .env.example is the documented reference render.
    env_file="$DOCKER_DIR/.env.example"
  fi

  local -a env_args=()
  if [[ -n "$env_file" ]]; then
    env_args=(--env-file "$env_file")
  fi

  err_file="$(mktemp "${TMPDIR:-/tmp}/ha-accept.XXXXXX")"
  if ! services="$(docker compose \
      --project-name "$COMPOSE_PROJECT" \
      --project-directory "$DOCKER_DIR" \
      "${env_args[@]}" \
      "${files[@]}" \
      --profile "$profile" \
      config --services 2>"$err_file")"; then
    COMPOSE_CONFIG_ERROR="$(tr -d '\r' < "$err_file" | head -n 20)"
    rm -f "$err_file"
    return 1
  fi
  rm -f "$err_file"
  printf '%s\n' "$services" | tr -d '\r'
  return 0
}

required_services() {
  case "$MODE" in
    sentinel) printf '%s\n' redis-1 redis-2 redis-3 sentinel-1 sentinel-2 sentinel-3 ;;
    mysql) printf '%s\n' mysql-1 mysql-2 mysql-3 mysql-router ;;
  esac
}

check_required_services() { # <services blob> -> prints PASS/FAIL, returns 1 if missing
  local services="$1" service missing=()
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    if ! grep -qxF "$service" <<<"$services"; then
      missing+=("$service")
    fi
  done < <(required_services)

  if (( ${#missing[@]} > 0 )); then
    fail "required compose services are rendered" "missing: ${missing[*]}"
    return 1
  fi
  pass "required compose services are rendered" "$(required_services | paste -sd, -)"
  return 0
}

check_container() { # <container> <label>
  local container="$1" label="$2" state
  state="$(container_state "$container")"
  if [[ "$state" == "running healthy" || "$state" == "running no-healthcheck" ]]; then
    pass "$label is running" "$container: $state"
  else
    fail "$label is running" "$container: $state"
  fi
}

actions_are_empty() {
  controller_call GET /chaos/actions
  [[ "$HTTP_CODE" == "200" ]] \
    && grep -Eq '"data"[[:space:]]*:[[:space:]]*\[[[:space:]]*\]' <<<"$HTTP_BODY"
}

# ---------------------------------------------------------------------------
# Redis Sentinel helpers
# ---------------------------------------------------------------------------

redis_info() { # <container> -> INFO replication text
  docker exec -e REDISCLI_AUTH="$SENTINEL_PASSWORD" "$1" \
    timeout "$EXEC_TIMEOUT_SECONDS" redis-cli -p "$SENTINEL_NODE_PORT" --raw info replication 2>/dev/null \
    | tr -d '\r' || true
}

sentinel_cli() { # <container> <sentinel args...>
  local container="$1"
  shift
  docker exec "$container" \
    timeout "$EXEC_TIMEOUT_SECONDS" redis-cli -p "$SENTINEL_PORT" --raw "$@" 2>/dev/null \
    | tr -d '\r' || true
}

info_field() { # <info text> <field>
  grep -m1 -F -e "$2:" <<<"$1" | cut -d: -f2- | tr -d '\r' || true
}

sentinel_master_addr() { # <sentinel container> -> ip:port or empty
  sentinel_cli "$1" sentinel get-master-addr-by-name "$SENTINEL_MASTER_NAME" | paste -sd: -
}

find_redis_master() { # -> container name or empty
  local container info role
  for container in $SENTINEL_DATA_CONTAINERS; do
    info="$(redis_info "$container")"
    role="$(info_field "$info" role)"
    if [[ "$role" == "master" ]]; then
      printf '%s\n' "$container"
      return 0
    fi
  done
  return 0
}

# ---------------------------------------------------------------------------
# MySQL helpers
# ---------------------------------------------------------------------------

mysql_route_probe() { # <probe-container> <router-port> -> "server_id<TAB>read_only"
  local probe="$1" port="$2" remote raw out
  remote="MYSQL_PWD=\"\${${MYSQL_PASSWORD_ENV}:-}\" exec timeout ${EXEC_TIMEOUT_SECONDS} ${MYSQL_BIN} \
--connect-timeout=${MYSQL_CONNECT_TIMEOUT_SECONDS} \
-N -B -h '${MYSQL_ROUTER_HOST}' -P '${port}' -u '${MYSQL_ADMIN_USER}' \
-e 'SELECT @@server_id, @@read_only'"
  raw="$(docker exec "$probe" bash -c "$remote" 2>&1 || true)"
  out="$(printf '%s\n' "$raw" | tr -d '\r' | grep -E '^[0-9]+[[:space:]]+[0-9]+$' | tail -n 1)"
  if [[ -z "$out" ]]; then
    MYSQL_PROBE_ERROR="$(printf '%s' "$raw" | tr -d '\r' | head -n 2 | paste -sd' ' -)"
    return 1
  fi
  printf '%s\n' "$out"
  return 0
}

run_cluster_status() { # [probe-container] -> CLUSTER_STATUS_OUTPUT / CLUSTER_STATUS_RC
  local probe="${1:-$MYSQL_PROBE_CONTAINER}"
  if CLUSTER_STATUS_OUTPUT="$(DB_CLUSTER_STATUS_PROBE_CONTAINER="$probe" bash "$STATUS_SCRIPT" 2>&1)"; then
    CLUSTER_STATUS_RC=0
  else
    CLUSTER_STATUS_RC=$?
  fi
  CLUSTER_STATUS_OUTPUT="$(printf '%s' "$CLUSTER_STATUS_OUTPUT" | tr -d '\r')"
  return 0
}

mysql_member_token() { # <container> -> "mysql-2:3306 ONLINE PRIMARY" or empty
  # db-cluster-status.sh prints the whole GROUP_CONCAT membership on EVERY node
  # row, so a grep over a row either returns the reporting container (wrong when
  # the PRIMARY is mysql-2 but row 1 is jl-mysql-1) or matches another member's
  # role. Resolve the token whose host is this container's own service name:
  # container jl-mysql-2 -> member host mysql-2.
  local container="$1" member host
  while IFS= read -r member; do
    [[ -n "$member" ]] || continue
    host="${member%%:*}"
    if [[ "$container" == *"$host" ]]; then
      printf '%s\n' "$member"
      return 0
    fi
  done < <(printf '%s\n' "$CLUSTER_STATUS_OUTPUT" \
    | grep -oE '[A-Za-z0-9_.-]+:[0-9]+ ONLINE [A-Z]+' | sort -u || true)
  return 0
}

cluster_primary_container() { # from CLUSTER_STATUS_OUTPUT
  local container member
  for container in $MYSQL_NODES; do
    member="$(mysql_member_token "$container")"
    if [[ "$member" == *" ONLINE PRIMARY" ]]; then
      printf '%s\n' "$container"
      return 0
    fi
  done
  return 0
}

cluster_line_for() { # <container> -> that member's own "host:port STATE ROLE"
  mysql_member_token "$1"
}

select_mysql_probe() { # <target> -> container name (non-target, running)
  local target="$1" container
  if [[ "$MYSQL_PROBE_CONTAINER" != "auto" ]]; then
    if [[ "$MYSQL_PROBE_CONTAINER" == "$target" ]]; then
      printf '%s\n' "$MYSQL_PROBE_CONTAINER"
      return 1
    fi
    printf '%s\n' "$MYSQL_PROBE_CONTAINER"
    return 0
  fi
  for container in $MYSQL_NODES; do
    if [[ "$container" != "$target" ]] && container_running "$container"; then
      printf '%s\n' "$container"
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------------
# Preflight pieces
# ---------------------------------------------------------------------------

preflight_fail_exit() {
  fail "$1" "${2:-}"
  exit 1
}

check_controller() {
  http_call GET "$CONTROLLER_URL/health"
  if [[ "$HTTP_CODE" == "200" ]] && grep -q '"status":"UP"' <<<"$HTTP_BODY"; then
    pass "chaos-controller is reachable" "GET $CONTROLLER_URL/health -> 200"
  else
    fail "chaos-controller is reachable" "GET $CONTROLLER_URL/health -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
  fi
}

check_http_baseline() {
  http_call GET "$NGINX_URL$PRODUCT_PATH"
  if [[ "$HTTP_CODE" == "200" ]]; then
    pass "Nginx product read works before the drill" "GET $PRODUCT_PATH -> 200"
  else
    fail "Nginx product read works before the drill" "GET $PRODUCT_PATH -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
  fi
}

check_no_active_actions() {
  if actions_are_empty; then
    pass "no pre-existing chaos action is active" "GET /chaos/actions -> data:[]"
  else
    fail "no pre-existing chaos action is active" \
      "GET /chaos/actions -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY"); stop-all would revert another run"
  fi
}

check_allow_list() { # <target>
  local target="$1" pattern
  pattern="$(container_env "$CONTROLLER_CONTAINER" CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN)"
  if [[ -z "$pattern" ]]; then
    # Surface the real controller answer too: an empty pattern is refuse-all.
    controller_call POST "/chaos/instances/${target}/actions" '{"action":"start"}'
    fail "target is in the controller datastore allow-list" \
      "CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN is empty (refuse-all); controller says HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
    return 1
  fi

  if [[ ! "$target" =~ ^($pattern)$ ]]; then
    # The start action is idempotent on a running container and still goes
    # through the same policy; use it to surface the controller's exact 403.
    controller_call POST "/chaos/instances/${target}/actions" '{"action":"start"}'
    fail "target is in the controller datastore allow-list" \
      "target \"$target\" does not match \"$pattern\"; controller says HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
    return 1
  fi

  # Even when the local match succeeds, ask the live controller. This proves the
  # running container's env (not just .env) admits the target; start is idempotent.
  controller_call POST "/chaos/instances/${target}/actions" '{"action":"start"}'
  if [[ "$HTTP_CODE" == "202" || "$HTTP_CODE" == "200" ]] \
     && [[ "$(json_string status "$HTTP_BODY")" == "completed" ]]; then
    pass "target is in the controller datastore allow-list" \
      "$target matches \"$pattern\"; idempotent start -> HTTP $HTTP_CODE"
    return 0
  fi
  fail "target is in the controller datastore allow-list" \
    "idempotent start -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
  return 1
}

check_ttl_budget() { # <requested-ttl>
  local ttl="$1" max
  max="$(container_env "$CONTROLLER_CONTAINER" CHAOS_MAX_TTL_SECONDS)"
  max="${max:-$(env_default CHAOS_MAX_TTL_SECONDS 600)}"
  if [[ "$max" =~ ^[0-9]+$ ]] && (( ttl <= max )); then
    pass "requested TTL is within the controller maximum" "ttl=${ttl}s max=${max}s"
  else
    fail "requested TTL is within the controller maximum" "ttl=${ttl}s max=${max:-unknown}s"
  fi
}

check_data_nodes() {
  local container
  for container in $SENTINEL_DATA_CONTAINERS; do
    check_container "$container" "Sentinel data node"
  done
  for container in $SENTINEL_CONTAINERS; do
    check_container "$container" "Sentinel"
  done
}

check_apps() {
  local container
  for container in $APP_CONTAINERS; do
    check_container "$container" "app instance"
  done
}

check_app_redis_mode() {
  local container mode
  for container in $APP_CONTAINERS; do
    mode="$(container_env "$container" REDIS_MODE)"
    if [[ "$mode" == "sentinel" ]]; then
      pass "app instance uses the Sentinel client" "$container: REDIS_MODE=sentinel"
    elif [[ "$SENTINEL_REQUIRE_SENTINEL_MODE" == "true" ]]; then
      fail "app instance uses the Sentinel client" \
        "$container: REDIS_MODE=${mode:-unset}; recreate app-1/2/3 with --profile ha (step-24d section 3)"
    else
      warn "app instance does not use the Sentinel client" \
        "$container: REDIS_MODE=${mode:-unset}; Nginx recovery only proves the app tier stayed up"
    fi
  done
}

check_app_mysql_mode() {
  local container mode
  for container in $APP_CONTAINERS; do
    mode="$(container_env "$container" MYSQL_MODE)"
    if [[ "$mode" == "router" ]]; then
      pass "app instance uses the MySQL Router endpoints" "$container: MYSQL_MODE=router"
    elif [[ "$MYSQL_REQUIRE_ROUTER_MODE" == "true" ]]; then
      fail "app instance uses the MySQL Router endpoints" \
        "$container: MYSQL_MODE=${mode:-unset}; set MYSQL_MODE=router + DB_WRITE_URL/DB_READ_URL and recreate the app tier (step-31)"
    else
      warn "app instance does not use the MySQL Router endpoints" \
        "$container: MYSQL_MODE=${mode:-unset}; the 6446 assertion still proves Router behavior, Nginx does not"
    fi
  done
}

warn_controller_probe_target() { # <target>
  local target="$1" probe
  probe="$(container_env "$CONTROLLER_CONTAINER" CHAOS_MYSQL_CLUSTER_CONTAINER)"
  probe="${probe:-${MYSQL_NODES%% *}}"
  if [[ "$probe" == "$target" ]]; then
    warn "controller MySQL cluster probe sits on the kill target" \
      "$probe == $target; mysql_cluster_* Prometheus series freeze during the drill. Point CHAOS_MYSQL_CLUSTER_CONTAINER at a non-target node (e.g. jl-mysql-3) in the controller environment; compose.base.yml does not forward this key from .env yet (step-45)"
  else
    pass "controller MySQL cluster probe is off the kill target" "$probe != $target"
  fi
}

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

ensure_target_running() {
  local deadline container_state_now
  container_state_now="$(container_state "$TARGET_CONTAINER")"
  if [[ "$container_state_now" == running* ]]; then
    pass "cleanup left the datastore running" "$TARGET_CONTAINER: $container_state_now"
    return 0
  fi

  # First remedy: the controller's own revert path (idempotent start).
  controller_call POST "/chaos/instances/${TARGET_CONTAINER}/actions" '{"action":"start"}'
  deadline=$(( $(now_ms) + STOP_BUDGET_SECONDS * 1000 ))
  while (( $(now_ms) < deadline )); do
    if container_running "$TARGET_CONTAINER"; then
      pass "cleanup started the datastore through the chaos API" "$TARGET_CONTAINER is running"
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done

  # Last resort: the controller may be down or may have lost its action registry
  # (in-memory, see step-21). Never end the run with a stopped datastore.
  if docker start "$TARGET_CONTAINER" >/dev/null 2>&1; then
    deadline=$(( $(now_ms) + STOP_BUDGET_SECONDS * 1000 ))
    while (( $(now_ms) < deadline )); do
      if container_running "$TARGET_CONTAINER"; then
        pass "cleanup started the datastore with docker start" "$TARGET_CONTAINER is running"
        return 0
      fi
      sleep "$POLL_INTERVAL_SECONDS"
    done
  fi

  fail "cleanup left the datastore running" "$TARGET_CONTAINER is $(container_state "$TARGET_CONTAINER")"
  return 1
}

cleanup_mysql_cluster() {
  local deadline rc probe="${MYSQL_PROBE:-${MYSQL_NODES%% *}}"
  deadline=$(( $(now_ms) + CLEANUP_CLUSTER_BUDGET_SECONDS * 1000 ))
  while :; do
    run_cluster_status "$probe"
    rc="$CLUSTER_STATUS_RC"
    if (( rc == 0 )) && grep -q '^RESULT: ok' <<<"$CLUSTER_STATUS_OUTPUT"; then
      pass "cleanup restored the InnoDB Cluster" "db-cluster-status.sh -> RESULT: ok"
      return 0
    fi
    if (( $(now_ms) >= deadline )); then
      break
    fi
    sleep "$MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS"
  done
  fail "cleanup restored the InnoDB Cluster" \
    "db-cluster-status.sh -> $(grep -E '^RESULT:' <<<"$CLUSTER_STATUS_OUTPUT" | tail -n 1 || true)"
  return 1
}

cleanup_sentinel_topology() {
  local deadline info role link
  deadline=$(( $(now_ms) + CLEANUP_CLUSTER_BUDGET_SECONDS * 1000 ))
  while :; do
    info="$(redis_info "$TARGET_CONTAINER")"
    role="$(info_field "$info" role)"
    link="$(info_field "$info" master_link_status)"
    if [[ "$role" == "slave" && "$link" == "up" ]]; then
      pass "cleanup restored the Sentinel replica link" \
        "$TARGET_CONTAINER: role=slave master_link_status=up"
      return 0
    fi
    if (( $(now_ms) >= deadline )); then
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  fail "cleanup restored the Sentinel replica link" \
    "$TARGET_CONTAINER: role=${role:-?} master_link_status=${link:-?}"
  return 1
}

run_cleanup() {
  local rc=0 deadline failed pending
  printf '\n== Cleanup ==\n'

  controller_call POST /chaos/stop-all
  if [[ "$HTTP_CODE" == "200" ]]; then
    failed="$(json_number failed "$HTTP_BODY")"
    pending="$(json_number pending "$HTTP_BODY")"
    if [[ "${failed:-0}" == "0" && "${pending:-0}" == "0" ]]; then
      pass "POST /chaos/stop-all reverted every action" "failed=0 pending=0"
    else
      fail "POST /chaos/stop-all reverted every action" \
        "failed=${failed:-?} pending=${pending:-?} body=$(body_snippet "$HTTP_BODY")"
      rc=1
    fi
  else
    fail "POST /chaos/stop-all reverted every action" \
      "HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
    rc=1
  fi

  deadline=$(( $(now_ms) + STOP_ALL_BUDGET_SECONDS * 1000 ))
  while :; do
    if actions_are_empty; then
      pass "no active chaos action remains" "GET /chaos/actions -> data:[]"
      break
    fi
    if (( $(now_ms) >= deadline )); then
      fail "no active chaos action remains" \
        "GET /chaos/actions -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
      rc=1
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done

  if [[ -n "$TARGET_CONTAINER" ]]; then
    ensure_target_running || rc=1
  fi

  if [[ "$MODE" == "mysql" && -n "$TARGET_CONTAINER" ]]; then
    cleanup_mysql_cluster || rc=1
  fi
  if [[ "$MODE" == "sentinel" && -n "$TARGET_CONTAINER" ]]; then
    cleanup_sentinel_topology || rc=1
  fi

  return "$rc"
}

on_exit() {
  local rc=$?
  trap - EXIT
  if [[ "$CLEANUP_ARMED" == true ]]; then
    if ! run_cleanup; then
      rc=1
    fi
  fi
  if (( FAILED > 0 )); then
    rc=1
  fi
  print_summary
  exit "$rc"
}

# ---------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------

check_dry_run_render() {
  local services missing=() service overlay
  case "$MODE" in
    sentinel) overlay="compose.ha.yml" ;;
    mysql) overlay="compose.mysql.yml" ;;
  esac
  if [[ ! -f "$DOCKER_DIR/$overlay" ]]; then
    skip "$MODE drill" "profile overlay $overlay is absent; nothing to plan"
    return 2
  fi
  if ! command -v docker >/dev/null 2>&1; then
    skip "compose render check" "docker CLI not available"
    return 2
  fi
  if ! services="$(compose_config_services)"; then
    fail "compose render check" "${COMPOSE_CONFIG_ERROR:-docker compose config failed}"
    return 1
  fi
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    if ! grep -qxF "$service" <<<"$services"; then
      missing+=("$service")
    fi
  done < <(required_services)

  if (( ${#missing[@]} > 0 )); then
    skip "$MODE drill" "required profile services are not in this compose render (missing: ${missing[*]}); nothing to plan"
    return 2
  fi
  printf 'CHECK compose render includes %s (%s services)\n' \
    "$(required_services | paste -sd, -)" "$(printf '%s\n' "$services" | grep -c .)"
  return 0
}

print_plan_header() {
  printf '== HA acceptance dry run: %s ==\n' "$MODE"
  printf 'Config (environment > %s > defaults):\n' "$ENV_FILE"
  printf '  controller          : %s\n' "$CONTROLLER_URL"
  printf '  nginx product probe : %s%s\n' "$NGINX_URL" "$PRODUCT_PATH"
  printf '  lab token           : set (value never printed)\n'
  printf '  poll interval       : %ss\n' "$POLL_INTERVAL_SECONDS"
  printf '  recovery threshold  : %ss\n' "$HTTP_RECOVERY_THRESHOLD_SECONDS"
  printf '  recovery budget     : %ss\n' "$HTTP_RECOVERY_BUDGET_SECONDS"
  if [[ "$MODE" == "sentinel" ]]; then
    printf '  data nodes          : %s\n' "$SENTINEL_DATA_CONTAINERS"
    printf '  sentinels           : %s\n' "$SENTINEL_CONTAINERS"
    printf '  master name / port  : %s / %s (Sentinel %s)\n' \
      "$SENTINEL_MASTER_NAME" "$SENTINEL_NODE_PORT" "$SENTINEL_PORT"
    printf '  target              : %s\n' "$SENTINEL_TARGET"
    printf '  kill TTL            : %ss\n' "$SENTINEL_TTL_SECONDS"
    printf '  promotion budget    : %ss\n' "$SENTINEL_PROMOTION_BUDGET_SECONDS"
    printf '  rejoin budget       : %ss\n' "$SENTINEL_REJOIN_BUDGET_SECONDS"
  else
    printf '  mysql nodes         : %s\n' "$MYSQL_NODES"
    printf '  router              : %s:%s\n' "$MYSQL_ROUTER_HOST" "$MYSQL_ROUTER_RW_PORT"
    printf '  probe container     : %s\n' "$MYSQL_PROBE_CONTAINER"
    printf '  target              : %s\n' "$MYSQL_TARGET"
    printf '  kill TTL            : %ss\n' "$MYSQL_TTL_SECONDS"
    printf '  promotion budget    : %ss\n' "$MYSQL_PROMOTION_BUDGET_SECONDS"
    printf '  rejoin budget       : %ss\n' "$MYSQL_REJOIN_BUDGET_SECONDS"
    printf '  require router mode : %s\n' "$MYSQL_REQUIRE_ROUTER_MODE"
  fi
  printf '\n'
}

print_plan_steps() {
  if [[ "$MODE" == "sentinel" ]]; then
    cat <<EOF
Planned steps:
  1. preflight: lab .env, controller /health, ha profile services + healthy
     containers, live Sentinel master resolution, app REDIS_MODE=sentinel,
     no active chaos action, controller datastore allow-list, TTL budget.
  2. POST /chaos/instances/<current-master>/actions {"action":"kill","ttlSeconds":$SENTINEL_TTL_SECONDS}
  3. poll every ${POLL_INTERVAL_SECONDS}s (budget ${SENTINEL_PROMOTION_BUDGET_SECONDS}s) for:
     get-master-addr-by-name changes + a different data node reports role=master.
  4. poll $NGINX_URL$PRODUCT_PATH every ${POLL_INTERVAL_SECONDS}s (budget ${HTTP_RECOVERY_BUDGET_SECONDS}s)
     and require the first 200 within ${HTTP_RECOVERY_THRESHOLD_SECONDS}s.
  5. wait for the $SENTINEL_TTL_SECONDS s TTL to restart the old master, then require
     role=slave + master_link_status=up (budget ${SENTINEL_REJOIN_BUDGET_SECONDS}s).
  6. always POST /chaos/stop-all, require no active action, require the target running.
EOF
  else
    cat <<EOF
Planned steps:
  1. preflight: lab .env, controller /health, db-ha profile services + healthy
     containers, db-cluster-status.sh RESULT: ok, current PRIMARY resolution,
     app MYSQL_MODE=router, no active chaos action, allow-list, TTL budget,
     Router 6446 baseline server_id/READ_WRITE.
  2. POST /chaos/instances/<current-primary>/actions {"action":"kill","ttlSeconds":$MYSQL_TTL_SECONDS}
  3. poll every ${POLL_INTERVAL_SECONDS}s (budget ${MYSQL_PROMOTION_BUDGET_SECONDS}s) for Router
     $MYSQL_ROUTER_HOST:$MYSQL_ROUTER_RW_PORT to answer READ_WRITE with a different server_id.
  4. poll $NGINX_URL$PRODUCT_PATH every ${POLL_INTERVAL_SECONDS}s (budget ${HTTP_RECOVERY_BUDGET_SECONDS}s)
     and require the first 200 within ${HTTP_RECOVERY_THRESHOLD_SECONDS}s.
  5. wait for the $MYSQL_TTL_SECONDS s TTL to restart the old node, then require
     ONLINE SECONDARY and db-cluster-status.sh RESULT: ok (budget ${MYSQL_REJOIN_BUDGET_SECONDS}s).
  6. always POST /chaos/stop-all, require no active action, require the target running
     and the cluster back to RESULT: ok.
EOF
  fi
  printf '\n'
}

run_dry_run() {
  print_plan_header
  print_plan_steps
  local render_rc=0
  check_dry_run_render || render_rc=$?
  if (( render_rc == 1 )); then
    print_summary
    exit 1
  fi
  if (( render_rc == 2 )); then
    print_summary
    exit 0
  fi
  skip "live preflight" "dry run: no controller, container or curl probe is executed"
  skip "chaos kill / TTL / HTTP polling" "dry run: nothing is killed"
  skip "cleanup" "dry run: stop-all is only sent after a real drill"
  printf 'Dry run complete.\n'
  print_summary
  exit 0
}

# ---------------------------------------------------------------------------
# Sentinel preflight and drill
# ---------------------------------------------------------------------------

sentinel_preflight() {
  local container info role link master_host master_count=0 resolved_master="" sentinel_addr="" first_sentinel=""
  local replica_ok=true replica_detail="" services=""

  check_controller
  if (( FAILED > 0 )); then exit 1; fi

  if ! services="$(compose_config_services)"; then
    preflight_fail_exit "compose render is valid" "${COMPOSE_CONFIG_ERROR:-docker compose config failed}"
  fi
  check_required_services "$services" || exit 1

  check_data_nodes
  check_apps
  check_container "$NGINX_CONTAINER" "Nginx"
  check_container "$CONTROLLER_CONTAINER" "chaos-controller"
  if (( FAILED > 0 )); then exit 1; fi

  check_app_redis_mode
  if (( FAILED > 0 )); then exit 1; fi

  for container in $SENTINEL_DATA_CONTAINERS; do
    info="$(redis_info "$container")"
    role="$(info_field "$info" role)"
    if [[ "$role" == "master" ]]; then
      master_count=$((master_count + 1))
      resolved_master="$container"
    else
      link="$(info_field "$info" master_link_status)"
      master_host="$(info_field "$info" master_host)"
      if [[ "$role" != "slave" || "$link" != "up" ]]; then
        replica_ok=false
        replica_detail="${replica_detail}${container}(role=${role:-?},link=${link:-?},master=${master_host:-?}) "
      fi
    fi
  done
  if (( master_count != 1 )); then
    preflight_fail_exit "exactly one Redis master is visible" \
      "found ${master_count} master(s) across: $SENTINEL_DATA_CONTAINERS"
  fi
  pass "exactly one Redis master is visible" "$resolved_master"
  if [[ "$replica_ok" == true ]]; then
    pass "every replica is linked to the master" "role=slave master_link_status=up"
  else
    fail "every replica is linked to the master" "$replica_detail"
  fi
  if (( FAILED > 0 )); then exit 1; fi

  if [[ "$SENTINEL_TARGET" == "auto" ]]; then
    TARGET_CONTAINER="$resolved_master"
  else
    if ! word_in_list "$SENTINEL_TARGET" "$SENTINEL_DATA_CONTAINERS"; then
      preflight_fail_exit "target is an HA Redis data node" \
        "HA_ACCEPT_SENTINEL_TARGET=$SENTINEL_TARGET is not in: $SENTINEL_DATA_CONTAINERS"
    fi
    if [[ "$SENTINEL_TARGET" != "$resolved_master" ]]; then
      preflight_fail_exit "explicit target is the current master" \
        "HA_ACCEPT_SENTINEL_TARGET=$SENTINEL_TARGET but the live master is $resolved_master; killing a replica is not the drill"
    fi
    TARGET_CONTAINER="$SENTINEL_TARGET"
  fi
  pass "sentinel drill target resolved" "$TARGET_CONTAINER (live master)"

  for container in $SENTINEL_CONTAINERS; do
    sentinel_addr="$(sentinel_master_addr "$container")"
    if [[ -n "$sentinel_addr" && -z "$first_sentinel" ]]; then
      first_sentinel="$container"
      BASELINE_MASTER_ADDRESS="$sentinel_addr"
    fi
    if [[ -z "$sentinel_addr" ]]; then
      fail "every Sentinel reports the master address" "$container returned no address"
    fi
  done
  if (( FAILED > 0 )); then exit 1; fi
  if [[ -z "${BASELINE_MASTER_ADDRESS:-}" ]]; then
    preflight_fail_exit "Sentinel reports the master address" "all sentinels returned no get-master-addr-by-name"
  fi
  pass "Sentinel reports the master address" "$first_sentinel -> $BASELINE_MASTER_ADDRESS"

  if address_matches_container "$BASELINE_MASTER_ADDRESS" "$TARGET_CONTAINER"; then
    pass "Sentinel address matches the live master container" "$BASELINE_MASTER_ADDRESS -> $TARGET_CONTAINER"
  else
    preflight_fail_exit "Sentinel address matches the live master container" \
      "$BASELINE_MASTER_ADDRESS does not match $TARGET_CONTAINER (ips: $(container_ipv4s "$TARGET_CONTAINER"))"
  fi

  check_no_active_actions
  if (( FAILED > 0 )); then exit 1; fi
  check_allow_list "$TARGET_CONTAINER" || exit 1
  check_ttl_budget "$SENTINEL_TTL_SECONDS"
  if (( FAILED > 0 )); then exit 1; fi
  check_http_baseline
  if (( FAILED > 0 )); then exit 1; fi
}

sentinel_drill() {
  local target="$TARGET_CONTAINER"
  local kill_start_ms kill_elapsed_ms deadline max_budget_ms
  local state info role link master_host address new_master err
  local promotion_done=false http_done=false rejoin_done=false kill_stopped=false

  printf '\n== Drill: Redis Sentinel ==\n'
  kill_start_ms="$(now_ms)"
  CLEANUP_ARMED=true
  controller_call POST "/chaos/instances/${target}/actions" \
    "{\"action\":\"kill\",\"ttlSeconds\":${SENTINEL_TTL_SECONDS}}" "$KILL_TIMEOUT_SECONDS"

  if [[ "$HTTP_CODE" == "202" || "$HTTP_CODE" == "200" ]] \
     && [[ "$(json_string target "$HTTP_BODY")" == "$target" ]] \
     && [[ "$(json_number ttlSeconds "$HTTP_BODY")" == "$SENTINEL_TTL_SECONDS" ]]; then
    pass "chaos accepted the Sentinel kill" \
      "target=$target ttl=${SENTINEL_TTL_SECONDS}s HTTP=$HTTP_CODE status=$(json_string status "$HTTP_BODY")"
  else
    fail "chaos accepted the Sentinel kill" \
      "target=$target HTTP=$HTTP_CODE body=$(body_snippet "$HTTP_BODY")"
    return 1
  fi

  # The kill is a docker stop: poll briefly until the target is no longer running.
  deadline=$(( $(now_ms) + STOP_BUDGET_SECONDS * 1000 ))
  while (( $(now_ms) < deadline )); do
    state="$(container_state "$target")"
    if [[ "$state" != running* ]]; then
      pass "kill stopped the master container" "$target: $state"
      kill_stopped=true
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  if [[ "$kill_stopped" != true ]]; then
    fail "kill stopped the master container" "$target is still $(container_state "$target")"
  fi

  max_budget_ms=$(( SENTINEL_PROMOTION_BUDGET_SECONDS > HTTP_RECOVERY_BUDGET_SECONDS ? SENTINEL_PROMOTION_BUDGET_SECONDS : HTTP_RECOVERY_BUDGET_SECONDS ))
  deadline=$(( kill_start_ms + max_budget_ms * 1000 ))
  while :; do
    kill_elapsed_ms=$(( $(now_ms) - kill_start_ms ))

    if [[ "$http_done" == false ]]; then
      http_call GET "$NGINX_URL$PRODUCT_PATH"
      if [[ "$HTTP_CODE" == "200" ]]; then
        HTTP_RECOVERY_MS="$kill_elapsed_ms"
        http_done=true
      fi
    fi

    if [[ "$promotion_done" == false ]]; then
      address="$(sentinel_master_addr "${SENTINEL_CONTAINERS%% *}")"
      if [[ -n "$address" && "$address" != "$BASELINE_MASTER_ADDRESS" ]]; then
        new_master="$(find_redis_master)"
        if [[ -n "$new_master" && "$new_master" != "$target" ]]; then
          NEW_MASTER_CONTAINER="$new_master"
          NEW_MASTER_ADDRESS="$address"
          PROMOTION_MS="$kill_elapsed_ms"
          promotion_done=true
        fi
      fi
    fi

    if [[ "$promotion_done" == true && "$http_done" == true ]]; then
      break
    fi
    if (( $(now_ms) >= deadline )); then
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done

  if [[ "$promotion_done" == true ]]; then
    pass "Sentinel promoted a different master" \
      "$BASELINE_MASTER_ADDRESS -> $NEW_MASTER_ADDRESS ($NEW_MASTER_CONTAINER) in $(fmt_ms "$PROMOTION_MS")"
    if address_matches_container "$NEW_MASTER_ADDRESS" "$NEW_MASTER_CONTAINER"; then
      pass "new Sentinel master address matches its container" "$NEW_MASTER_ADDRESS -> $NEW_MASTER_CONTAINER"
    else
      fail "new Sentinel master address matches its container" \
        "$NEW_MASTER_ADDRESS vs ips: $(container_ipv4s "$NEW_MASTER_CONTAINER")"
    fi
    if [[ "$SENTINEL_REQUIRE_AGREEMENT" == "true" ]]; then
      local sentinel agreement_ok=true
      for sentinel in $SENTINEL_CONTAINERS; do
        address="$(sentinel_master_addr "$sentinel")"
        if [[ "$address" != "$NEW_MASTER_ADDRESS" ]]; then
          agreement_ok=false
          err="$address"
        fi
      done
      if [[ "$agreement_ok" == true ]]; then
        pass "all Sentinels agree on the new master" "$NEW_MASTER_ADDRESS"
      else
        fail "all Sentinels agree on the new master" "last disagreement: ${err:-empty}"
      fi
    fi
  else
    fail "Sentinel promoted a different master" \
      "no new master within ${SENTINEL_PROMOTION_BUDGET_SECONDS}s (sentinel: $(sentinel_master_addr "${SENTINEL_CONTAINERS%% *}"))"
  fi

  if [[ "$http_done" == true ]] && (( HTTP_RECOVERY_MS <= HTTP_RECOVERY_THRESHOLD_SECONDS * 1000 )); then
    pass "Nginx product read recovered within the threshold" \
      "first 200 at $(fmt_ms "$HTTP_RECOVERY_MS") <= ${HTTP_RECOVERY_THRESHOLD_SECONDS}s"
  elif [[ "$http_done" == true ]]; then
    fail "Nginx product read recovered within the threshold" \
      "first 200 at $(fmt_ms "$HTTP_RECOVERY_MS") > ${HTTP_RECOVERY_THRESHOLD_SECONDS}s"
  else
    fail "Nginx product read recovered within the threshold" \
      "no 200 within ${HTTP_RECOVERY_BUDGET_SECONDS}s"
  fi

  if [[ "$promotion_done" != true ]]; then
    skip "TTL revert and replica rejoin" "no promotion was observed; cleanup will restart the target"
    return 0
  fi

  deadline=$(( kill_start_ms + SENTINEL_REJOIN_BUDGET_SECONDS * 1000 ))
  while :; do
    kill_elapsed_ms=$(( $(now_ms) - kill_start_ms ))
    state="$(container_state "$target")"
    if [[ "$state" == running* ]]; then
      if [[ -z "$TTL_REVERT_MS" ]]; then
        TTL_REVERT_MS="$kill_elapsed_ms"
      fi
      info="$(redis_info "$target")"
      role="$(info_field "$info" role)"
      link="$(info_field "$info" master_link_status)"
      master_host="$(info_field "$info" master_host)"
      if [[ "$role" == "slave" && "$link" == "up" ]]; then
        REJOIN_MS="$kill_elapsed_ms"
        rejoin_done=true
        break
      fi
    fi
    if (( $(now_ms) >= deadline )); then
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done

  if [[ -n "$TTL_REVERT_MS" ]]; then
    pass "TTL restarted the old master" \
      "$target is running at $(fmt_ms "$TTL_REVERT_MS") (ttl=${SENTINEL_TTL_SECONDS}s)"
  else
    fail "TTL restarted the old master" \
      "$target is $(container_state "$target") after ${SENTINEL_REJOIN_BUDGET_SECONDS}s"
  fi

  if [[ "$rejoin_done" == true ]]; then
    pass "old master rejoined as a replica" \
      "role=$role master_link_status=$link master_host=$master_host at $(fmt_ms "$REJOIN_MS")"
  else
    info="$(redis_info "$target")"
    fail "old master rejoined as a replica" \
      "role=$(info_field "$info" role) master_link_status=$(info_field "$info" master_link_status) after ${SENTINEL_REJOIN_BUDGET_SECONDS}s"
  fi

  if actions_are_empty; then
    pass "TTL revert left no active action" "GET /chaos/actions -> data:[]"
  else
    fail "TTL revert left no active action" \
      "GET /chaos/actions -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
  fi
}

# ---------------------------------------------------------------------------
# MySQL preflight and drill
# ---------------------------------------------------------------------------

mysql_preflight() {
  local services="" target="" route="" sid="" ro="" node

  check_controller
  if (( FAILED > 0 )); then exit 1; fi

  if ! services="$(compose_config_services)"; then
    preflight_fail_exit "compose render is valid" "${COMPOSE_CONFIG_ERROR:-docker compose config failed}"
  fi
  check_required_services "$services" || exit 1

  for node in $MYSQL_NODES; do
    check_container "$node" "MySQL node"
  done
  check_container "$MYSQL_ROUTER_CONTAINER" "MySQL Router"
  check_apps
  check_container "$NGINX_CONTAINER" "Nginx"
  check_container "$CONTROLLER_CONTAINER" "chaos-controller"
  if (( FAILED > 0 )); then exit 1; fi

  check_app_mysql_mode
  if (( FAILED > 0 )); then exit 1; fi

  # Contract: db-cluster-status.sh must report RESULT: ok before the drill.
  run_cluster_status "${MYSQL_NODES%% *}"
  printf '\n--- db-cluster-status.sh (preflight) ---\n%s\n\n' "$CLUSTER_STATUS_OUTPUT"
  if (( CLUSTER_STATUS_RC != 0 )) || ! grep -q '^RESULT: ok' <<<"$CLUSTER_STATUS_OUTPUT"; then
    preflight_fail_exit "db-cluster-status.sh reports RESULT: ok" \
      "exit=$CLUSTER_STATUS_RC $(grep -E '^RESULT:' <<<"$CLUSTER_STATUS_OUTPUT" | tail -n 1 || true)"
  fi
  pass "db-cluster-status.sh reports RESULT: ok" \
    "$(grep -E '^RESULT:' <<<"$CLUSTER_STATUS_OUTPUT" | tail -n 1)"

  target="$(cluster_primary_container)"
  if [[ -z "$target" ]]; then
    preflight_fail_exit "current InnoDB Cluster PRIMARY is resolvable" \
      "no 'ONLINE PRIMARY' row in db-cluster-status.sh output"
  fi
  if ! word_in_list "$target" "$MYSQL_NODES"; then
    preflight_fail_exit "current PRIMARY is an HA MySQL node" "$target is not in: $MYSQL_NODES"
  fi
  pass "current InnoDB Cluster PRIMARY is resolvable" "$target"

  if [[ "$MYSQL_TARGET" == "auto" ]]; then
    TARGET_CONTAINER="$target"
  else
    if ! word_in_list "$MYSQL_TARGET" "$MYSQL_NODES"; then
      preflight_fail_exit "target is an HA MySQL node" \
        "HA_ACCEPT_MYSQL_TARGET=$MYSQL_TARGET is not in: $MYSQL_NODES"
    fi
    if [[ "$MYSQL_TARGET" != "$target" ]]; then
      preflight_fail_exit "explicit target is the current PRIMARY" \
        "HA_ACCEPT_MYSQL_TARGET=$MYSQL_TARGET but db-cluster-status.sh reports $target; killing a SECONDARY is not the drill"
    fi
    TARGET_CONTAINER="$MYSQL_TARGET"
  fi
  pass "mysql drill target resolved" "$TARGET_CONTAINER (live PRIMARY)"

  if ! MYSQL_PROBE="$(select_mysql_probe "$TARGET_CONTAINER")"; then
    preflight_fail_exit "Router probe container is not the kill target" \
      "HA_ACCEPT_MYSQL_PROBE_CONTAINER=${MYSQL_PROBE_CONTAINER} is the target; the probe must survive the drill (e.g. jl-mysql-3)"
  fi
  if ! container_running "$MYSQL_PROBE"; then
    preflight_fail_exit "Router probe container is running" "$MYSQL_PROBE: $(container_state "$MYSQL_PROBE")"
  fi
  pass "Router probe container survives the drill" "$MYSQL_PROBE != $TARGET_CONTAINER"

  warn_controller_probe_target "$TARGET_CONTAINER"

  if route="$(mysql_route_probe "$MYSQL_PROBE" "$MYSQL_ROUTER_RW_PORT")"; then
    read -r sid ro <<<"$route"
    if [[ "$ro" == "0" ]]; then
      pass "Router 6446 starts in READ_WRITE" "server_id=$sid read_only=0"
      BASELINE_MYSQL_SERVER_ID="$sid"
    else
      fail "Router 6446 starts in READ_WRITE" "server_id=$sid read_only=$ro"
    fi
  else
    fail "Router 6446 starts in READ_WRITE" "${MYSQL_PROBE_ERROR:-probe failed}"
  fi

  check_no_active_actions
  if (( FAILED > 0 )); then exit 1; fi
  check_allow_list "$TARGET_CONTAINER" || exit 1
  check_ttl_budget "$MYSQL_TTL_SECONDS"
  if (( FAILED > 0 )); then exit 1; fi
  check_http_baseline
  if (( FAILED > 0 )); then exit 1; fi
}

mysql_drill() {
  local target="$TARGET_CONTAINER" probe="$MYSQL_PROBE"
  local kill_start_ms kill_elapsed_ms deadline max_budget_ms
  local route sid ro state line rc
  local promotion_done=false http_done=false rejoin_done=false kill_stopped=false
  local target_line="" cluster_ok=false

  printf '\n== Drill: MySQL Router ==\n'
  kill_start_ms="$(now_ms)"
  CLEANUP_ARMED=true
  controller_call POST "/chaos/instances/${target}/actions" \
    "{\"action\":\"kill\",\"ttlSeconds\":${MYSQL_TTL_SECONDS}}" "$KILL_TIMEOUT_SECONDS"

  if [[ "$HTTP_CODE" == "202" || "$HTTP_CODE" == "200" ]] \
     && [[ "$(json_string target "$HTTP_BODY")" == "$target" ]] \
     && [[ "$(json_number ttlSeconds "$HTTP_BODY")" == "$MYSQL_TTL_SECONDS" ]]; then
    pass "chaos accepted the MySQL kill" \
      "target=$target ttl=${MYSQL_TTL_SECONDS}s HTTP=$HTTP_CODE status=$(json_string status "$HTTP_BODY")"
  else
    fail "chaos accepted the MySQL kill" \
      "target=$target HTTP=$HTTP_CODE body=$(body_snippet "$HTTP_BODY")"
    return 1
  fi

  deadline=$(( $(now_ms) + STOP_BUDGET_SECONDS * 1000 ))
  while (( $(now_ms) < deadline )); do
    state="$(container_state "$target")"
    if [[ "$state" != running* ]]; then
      pass "kill stopped the PRIMARY container" "$target: $state"
      kill_stopped=true
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  if [[ "$kill_stopped" != true ]]; then
    fail "kill stopped the PRIMARY container" "$target is still $(container_state "$target")"
  fi

  max_budget_ms=$(( MYSQL_PROMOTION_BUDGET_SECONDS > HTTP_RECOVERY_BUDGET_SECONDS ? MYSQL_PROMOTION_BUDGET_SECONDS : HTTP_RECOVERY_BUDGET_SECONDS ))
  deadline=$(( kill_start_ms + max_budget_ms * 1000 ))
  while :; do
    kill_elapsed_ms=$(( $(now_ms) - kill_start_ms ))

    if [[ "$http_done" == false ]]; then
      http_call GET "$NGINX_URL$PRODUCT_PATH"
      if [[ "$HTTP_CODE" == "200" ]]; then
        HTTP_RECOVERY_MS="$kill_elapsed_ms"
        http_done=true
      fi
    fi

    if [[ "$promotion_done" == false ]]; then
      if route="$(mysql_route_probe "$probe" "$MYSQL_ROUTER_RW_PORT")"; then
        read -r sid ro <<<"$route"
        if [[ "$sid" != "$BASELINE_MYSQL_SERVER_ID" && "$ro" == "0" ]]; then
          NEW_MYSQL_SERVER_ID="$sid"
          ROUTER_SWITCH_MS="$kill_elapsed_ms"
          promotion_done=true
        fi
      fi
    fi

    if [[ "$promotion_done" == true && "$http_done" == true ]]; then
      break
    fi
    if (( $(now_ms) >= deadline )); then
      break
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done

  if [[ "$promotion_done" == true ]]; then
    pass "Router 6446 moved to the new PRIMARY" \
      "server_id $BASELINE_MYSQL_SERVER_ID -> $NEW_MYSQL_SERVER_ID READ_WRITE in $(fmt_ms "$ROUTER_SWITCH_MS")"
  else
    fail "Router 6446 moved to the new PRIMARY" \
      "server_id stayed $BASELINE_MYSQL_SERVER_ID or route was not READ_WRITE within ${MYSQL_PROMOTION_BUDGET_SECONDS}s"
  fi

  if [[ "$http_done" == true ]] && (( HTTP_RECOVERY_MS <= HTTP_RECOVERY_THRESHOLD_SECONDS * 1000 )); then
    pass "Nginx product read recovered within the threshold" \
      "first 200 at $(fmt_ms "$HTTP_RECOVERY_MS") <= ${HTTP_RECOVERY_THRESHOLD_SECONDS}s"
  elif [[ "$http_done" == true ]]; then
    fail "Nginx product read recovered within the threshold" \
      "first 200 at $(fmt_ms "$HTTP_RECOVERY_MS") > ${HTTP_RECOVERY_THRESHOLD_SECONDS}s"
  else
    fail "Nginx product read recovered within the threshold" \
      "no 200 within ${HTTP_RECOVERY_BUDGET_SECONDS}s"
  fi

  if [[ "$promotion_done" != true ]]; then
    skip "TTL revert and ONLINE SECONDARY rejoin" "no Router switch was observed; cleanup will restart the target"
    return 0
  fi

  deadline=$(( kill_start_ms + MYSQL_REJOIN_BUDGET_SECONDS * 1000 ))
  while :; do
    kill_elapsed_ms=$(( $(now_ms) - kill_start_ms ))
    state="$(container_state "$target")"
    if [[ "$state" == running* ]]; then
      if [[ -z "$TTL_REVERT_MS" ]]; then
        TTL_REVERT_MS="$kill_elapsed_ms"
      fi
      run_cluster_status "$probe"
      target_line="$(cluster_line_for "$target")"
      cluster_ok=false
      if (( CLUSTER_STATUS_RC == 0 )) && grep -q '^RESULT: ok' <<<"$CLUSTER_STATUS_OUTPUT"; then
        cluster_ok=true
      fi
      if [[ "$target_line" == *"ONLINE SECONDARY"* && "$cluster_ok" == true ]]; then
        REJOIN_MS="$kill_elapsed_ms"
        rejoin_done=true
        break
      fi
    fi
    if (( $(now_ms) >= deadline )); then
      break
    fi
    sleep "$MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS"
  done

  if [[ -n "$TTL_REVERT_MS" ]]; then
    pass "TTL restarted the old PRIMARY" \
      "$target is running at $(fmt_ms "$TTL_REVERT_MS") (ttl=${MYSQL_TTL_SECONDS}s)"
  else
    fail "TTL restarted the old PRIMARY" \
      "$target is $(container_state "$target") after ${MYSQL_REJOIN_BUDGET_SECONDS}s"
  fi

  if [[ "$rejoin_done" == true ]]; then
    pass "old PRIMARY rejoined as ONLINE SECONDARY" \
      "$(printf '%s' "$target_line" | sed -E 's/[[:space:]]+/ /g' | cut -c1-120) at $(fmt_ms "$REJOIN_MS")"
  else
    fail "old PRIMARY rejoined as ONLINE SECONDARY" \
      "target line: ${target_line:-<missing>}; $(grep -E '^RESULT:' <<<"$CLUSTER_STATUS_OUTPUT" | tail -n 1 || true)"
  fi

  if [[ "$cluster_ok" == true ]]; then
    pass "db-cluster-status.sh is back to RESULT: ok" \
      "$(grep -E '^RESULT:' <<<"$CLUSTER_STATUS_OUTPUT" | tail -n 1)"
  else
    fail "db-cluster-status.sh is back to RESULT: ok" \
      "exit=$CLUSTER_STATUS_RC $(grep -E '^RESULT:' <<<"$CLUSTER_STATUS_OUTPUT" | tail -n 1 || true)"
  fi

  if route="$(mysql_route_probe "$probe" "$MYSQL_ROUTER_RW_PORT")"; then
    read -r sid ro <<<"$route"
    if [[ "$ro" == "0" && "$sid" == "$NEW_MYSQL_SERVER_ID" ]]; then
      pass "Router 6446 still serves READ_WRITE after the rejoin" "server_id=$sid"
    else
      fail "Router 6446 still serves READ_WRITE after the rejoin" "server_id=$sid read_only=$ro (expected $NEW_MYSQL_SERVER_ID/0)"
    fi
  else
    fail "Router 6446 still serves READ_WRITE after the rejoin" "${MYSQL_PROBE_ERROR:-probe failed}"
  fi

  if actions_are_empty; then
    pass "TTL revert left no active action" "GET /chaos/actions -> data:[]"
  else
    fail "TTL revert left no active action" \
      "GET /chaos/actions -> HTTP $HTTP_CODE $(body_snippet "$HTTP_BODY")"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if [[ "$DRY_RUN" == true ]]; then
  run_dry_run
fi

# A real run must have the lab .env: compose, controller env and script defaults
# otherwise disagree silently. Use HA_ACCEPT_ENV_FILE to point elsewhere.
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run code/lab/scripts/up.sh first, or set HA_ACCEPT_ENV_FILE." >&2
  exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker CLI not found in PATH." >&2
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running or not reachable." >&2
  exit 2
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl not found in PATH." >&2
  exit 2
fi

trap on_exit EXIT

printf '== HA acceptance: %s ==\n' "$MODE_LABEL"
printf 'Target precedence: process environment > %s > script defaults\n' "$ENV_FILE"

if [[ "$MODE" == "sentinel" ]]; then
  sentinel_preflight
  sentinel_drill
else
  mysql_preflight
  mysql_drill
fi

exit 0
