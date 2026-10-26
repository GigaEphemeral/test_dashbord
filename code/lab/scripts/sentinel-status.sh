#!/usr/bin/env bash
set -euo pipefail

# Read-only viewer for the Redis Sentinel topology (compose profile "ha").
#
# Teaching aid for the M3 failover demo: it prints what the Sentinels believe
# (monitored master address, flags, replica/Sentinel discovery) next to what each
# data node reports about itself (role, replication link state).
#
# STRICTLY READ-ONLY: it only runs INFO and the read-only SENTINEL subcommands
# (get-master-addr-by-name, masters, master, replicas, sentinels). It never issues a
# write, never `SENTINEL failover`, never REPLICAOF/FLUSHDB/CONFIG SET, so running it
# in the middle of a drill cannot change the outcome.
#
# Every value is configurable; the process environment beats code/lab/docker/.env,
# which beats the defaults below (see .env.example for the documented list).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"

usage() {
  cat <<'EOF'
Usage: sentinel-status.sh [--watch [SECONDS]] [-n COUNT] [-h|--help]

Print the Redis Sentinel topology (master / replicas / Sentinels) from inside the
jl-net containers. Read-only: no failover, no writes, no state changes.

Options:
  --watch [SECONDS]  refresh every SECONDS until -n COUNT refreshes are done.
                     Handy to keep on screen while you stop the master and watch
                     the promotion. Without SECONDS the interval falls back to
                     SENTINEL_STATUS_WATCH_INTERVAL_SECONDS (default 2).
  -n, --count N      number of refreshes when using --watch (default 15)
  -h, --help         show this help

Environment (documented in code/lab/docker/.env.example):
  SENTINEL_STATUS_DATA_CONTAINERS        space-separated data-node containers
  SENTINEL_STATUS_SENTINEL_CONTAINERS    space-separated Sentinel containers
  SENTINEL_STATUS_EXEC_TIMEOUT_SECONDS   per-command budget inside the container
  SENTINEL_STATUS_WATCH_INTERVAL_SECONDS
  REDIS_HA_MASTER_NAME / REDIS_HA_NODE_PORT / REDIS_HA_SENTINEL_PORT / REDIS_HA_PASSWORD
EOF
}

WATCH=false
WATCH_COUNT=15
WATCH_INTERVAL_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --watch)
      WATCH=true
      shift
      # `--watch 5` reads as "every 5 seconds", like the other lab tools.
      if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
        WATCH_INTERVAL_ARG="$1"
        shift
      fi
      ;;
    -n|--count)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: $1 requires a value." >&2
        exit 2
      fi
      WATCH_COUNT="$2"
      shift 2
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

# Fill unset variables from .env. Only key names are ever touched here: the values
# may be lab credentials, so nothing read from the file is printed.
if [[ -f "$ENV_FILE" ]]; then
  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    line="${raw_line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    # Strip one layer of surrounding quotes if present. The quote characters come
    # from variables because a literal ' inside ${...} confuses the parser.
    dquote='"'
    squote="'"
    value="${value%$dquote}"; value="${value#$dquote}"
    value="${value%$squote}"; value="${value#$squote}"
    if [[ -z "${!key:-}" ]]; then
      export "$key=$value"
    fi
  done < "$ENV_FILE"
fi

DATA_CONTAINERS="${SENTINEL_STATUS_DATA_CONTAINERS:-jl-redis-1 jl-redis-2 jl-redis-3}"
SENTINEL_CONTAINERS="${SENTINEL_STATUS_SENTINEL_CONTAINERS:-jl-sentinel-1 jl-sentinel-2 jl-sentinel-3}"
EXEC_TIMEOUT="${SENTINEL_STATUS_EXEC_TIMEOUT_SECONDS:-5}"
# Priority: an explicit `--watch SECONDS` beats the environment/.env key, which beats
# the built-in 2. .env.example ships SENTINEL_STATUS_WATCH_INTERVAL_SECONDS=2, so the
# old order (env first) silently ignored every `--watch 5` argument.
WATCH_INTERVAL="${WATCH_INTERVAL_ARG:-${SENTINEL_STATUS_WATCH_INTERVAL_SECONDS:-2}}"
MASTER_NAME="${REDIS_HA_MASTER_NAME:-mymaster}"
NODE_PORT="${REDIS_HA_NODE_PORT:-6379}"
SENTINEL_PORT="${REDIS_HA_SENTINEL_PORT:-26379}"
NODE_PASSWORD="${REDIS_HA_PASSWORD:-}"

# REDISCLI_AUTH keeps the password out of the printed output and out of redis-cli's
# own "using -a is insecure" warning. Sentinels are queried unauthenticated: only
# Sentinel -> data-node traffic carries the password (sentinel auth-pass).
node_cli() {
  local container="$1"
  shift
  docker exec -e REDISCLI_AUTH="$NODE_PASSWORD" "$container" \
    timeout "$EXEC_TIMEOUT" redis-cli -p "$NODE_PORT" --raw "$@" 2>/dev/null | tr -d '\r' || true
}

sentinel_cli() {
  local container="$1"
  shift
  docker exec "$container" \
    timeout "$EXEC_TIMEOUT" redis-cli -p "$SENTINEL_PORT" --raw "$@" 2>/dev/null | tr -d '\r' || true
}

container_state() {
  docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null | tr -d '\r' || echo "missing"
}

# SENTINEL replies come back as flat key/value line pairs under --raw, so the value
# is simply the line after the key.
pair_value() {
  local input="$1" key="$2" previous="" line
  while IFS= read -r line; do
    if [[ "$previous" == "$key" ]]; then
      printf '%s\n' "$line"
      return 0
    fi
    previous="$line"
  done <<<"$input"
  return 0
}

# INFO sections are plain "field:value" lines.
info_field() {
  grep -m1 -F -e "$2:" <<<"$1" | cut -d: -f2- | tr -d '\r' || true
}

# SENTINEL REPLICAS / SENTINEL SENTINELS reply with one flat key/value block per
# entry. Every entry carries exactly one "runid" key line, so counting those lines
# is the reliable way to get the number of discovered entries.
count_entries() {
  local count
  count="$(grep -cxF -e runid <<<"$1" || true)"
  printf '%s\n' "${count:-0}"
}

# ... and because "ip" is immediately followed by "port" in each block, the pairs
# can be listed as endpoints for the demo.
entry_endpoints() {
  local input="$1" previous="" line endpoint_ip=""
  while IFS= read -r line; do
    if [[ "$previous" == "ip" ]]; then
      endpoint_ip="$line"
    elif [[ "$previous" == "port" && -n "$endpoint_ip" ]]; then
      printf '%s:%s ' "$endpoint_ip" "$line"
      endpoint_ip=""
    fi
    previous="$line"
  done <<<"$input"
}

sentinel_section() {
  local container
  echo
  echo "--- Sentinels (port $SENTINEL_PORT) ---"
  for container in $SENTINEL_CONTAINERS; do
    local state addr masters info replicas_reply sentinels_reply
    state="$(container_state "$container")"
    echo
    echo "[$container] state=$state"
    if [[ "$state" != "running" ]]; then
      echo "  (not running; nothing to report)"
      continue
    fi
    addr="$(sentinel_cli "$container" sentinel get-master-addr-by-name "$MASTER_NAME" | paste -sd: -)"
    masters="$(sentinel_cli "$container" sentinel master "$MASTER_NAME")"
    info="$(sentinel_cli "$container" info sentinel)"
    replicas_reply="$(sentinel_cli "$container" sentinel replicas "$MASTER_NAME")"
    sentinels_reply="$(sentinel_cli "$container" sentinel sentinels "$MASTER_NAME")"
    echo "  get-master-addr-by-name   : ${addr:-<empty>}"
    echo "  monitored address         : $(pair_value "$masters" ip):$(pair_value "$masters" port)"
    echo "  master flags              : $(pair_value "$masters" flags)"
    echo "  quorum / parallel-syncs   : $(pair_value "$masters" quorum) / $(pair_value "$masters" parallel-syncs)"
    echo "  failover-state            : $(pair_value "$masters" failover-state)"
    # num-other-sentinels / num-slaves exclude the Sentinel answering the question.
    echo "  known slaves / sentinels  : $(pair_value "$masters" num-slaves) / $(pair_value "$masters" num-other-sentinels)"
    echo "  master0 summary           : $(grep -m1 -E '^master0:' <<<"$info" || echo '<none>')"
    echo "  replicas listed           : $(count_entries "$replicas_reply") $(entry_endpoints "$replicas_reply")"
    echo "  peer sentinels listed     : $(count_entries "$sentinels_reply") $(entry_endpoints "$sentinels_reply")"
  done
}

data_section() {
  local container
  echo
  echo "--- Data nodes (port $NODE_PORT, role from INFO) ---"
  for container in $DATA_CONTAINERS; do
    local state info role
    state="$(container_state "$container")"
    echo
    echo "[$container] state=$state"
    if [[ "$state" != "running" ]]; then
      echo "  (not running; nothing to report)"
      continue
    fi
    # One INFO read; "info all" so the load fields from the clients/stats sections are
    # available next to the replication section that proves who follows whom.
    info="$(node_cli "$container" info all)"
    role="$(info_field "$info" role)"
    echo "  role                    : ${role:-<unknown>}"
    echo "  connected_slaves        : $(info_field "$info" connected_slaves)"
    echo "  master_host:port        : $(info_field "$info" master_host):$(info_field "$info" master_port)"
    echo "  master_link_status      : $(info_field "$info" master_link_status)"
    echo "  clients / ops per sec   : $(info_field "$info" connected_clients) clients, $(info_field "$info" instantaneous_ops_per_sec) ops/s"
  done
}

print_report() {
  echo "================================================================"
  echo "Redis Sentinel status - master name '$MASTER_NAME' - $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "================================================================"
  sentinel_section
  data_section
  echo
  echo "Read-only report. To rehearse the failover by hand (M3-1c owns the scripted"
  echo "drill): docker stop jl-redis-1, watch the promotion here, then docker start"
  echo "jl-redis-1 and confirm it comes back as a slave."
}

if $WATCH; then
  refresh=1
  while (( refresh <= WATCH_COUNT )); do
    clear 2>/dev/null || true
    echo "watching every ${WATCH_INTERVAL}s (refresh $refresh/$WATCH_COUNT) - Ctrl+C to stop"
    print_report
    if (( refresh == WATCH_COUNT )); then
      break
    fi
    sleep "$WATCH_INTERVAL"
    refresh=$(( refresh + 1 ))
  done
else
  print_report
fi
