#!/usr/bin/env bash
# Run one k6 scenario against the lab stack with Prometheus remote write.
#
#   bash code/lab/k6/run.sh smoke
#   LOAD_DURATION=2m bash code/lab/k6/run.sh load
#   K6_NETWORK=host BASE_URL=http://127.0.0.1:8080 bash code/lab/k6/run.sh smoke
#   LAB_TOKEN=... CHAOS_SCENARIO_FILE=quick-failover.json \
#     CHAOS_DURATION=30s bash code/lab/k6/run.sh ha-failover
#   LAB_TOKEN=... CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
#     CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
#     DB_FAILOVER_TARGET=jl-mysql-2 bash code/lab/k6/run.sh db-failover
#   LAB_TOKEN=... bash code/lab/k6/run.sh cache-lab   # opt-in: writes cache data
#
# The pinned image (grafana/k6:1.3.0, sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d)
# is resolved on the jl-net network by default, so BASE_URL=http://nginx.
# Every runtime value can be overridden from the environment; no host paths,
# URLs, ids or timeouts are hardcoded outside the defaults below.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"
K6_NETWORK="${K6_NETWORK:-jl-net}"
K6_DIR="${K6_DIR:-$SCRIPT_DIR}"
SCENARIO="${SCENARIO:-smoke}"
PROM_URL="${PROM_URL:-http://127.0.0.1:8080/prom}"
# Optional archive support (Task 20a): mount a host report directory at
# /reports and let k6 write its summary JSON into it. Both are off by default.
K6_REPORT_DIR="${K6_REPORT_DIR:-}"
K6_SUMMARY_EXPORT="${K6_SUMMARY_EXPORT:-}"
K6_REPORT_MOUNT="${K6_REPORT_MOUNT:-/reports}"
# Shared chaos scenario JSON (Task 22a), used only by the chaos scenarios.
# It is mounted read-only at a root-level container path: /scripts is mounted
# read-only, so Docker cannot create a nested mount point inside it, and
# /scripts/scenarios must stay the k6 JavaScript directory.
K6_SCENARIOS_DIR="${K6_SCENARIOS_DIR:-$SCRIPT_DIR/../scenarios}"
K6_SCENARIOS_MOUNT="${K6_SCENARIOS_MOUNT:-/chaos-scenarios}"

if [[ $# -gt 0 && "$1" != -* ]]; then
  SCENARIO="$1"
  shift
fi

# IS_CHAOS mounts the shared scenario JSON directory and forwards the
# chaos-controller variables; IS_CACHE only forwards the internal lab API
# settings (token + app base URL) for the cache experiment.
IS_CHAOS=0
IS_CACHE=0
case "$SCENARIO" in
  smoke|load|stress|spike|soak|d7) ;;
  ha-failover|db-failover) IS_CHAOS=1 ;;
  cache-lab) IS_CACHE=1 ;;
  *)
    printf 'ERROR: unknown scenario "%s" (expected smoke|load|stress|spike|soak|d7|ha-failover|db-failover|cache-lab).\n' "$SCENARIO" >&2
    exit 2
    ;;
esac

if [[ ! -d "$K6_DIR" ]]; then
  printf 'ERROR: k6 directory not found: %s\n' "$K6_DIR" >&2
  exit 2
fi
K6_DIR="$(cd "$K6_DIR" && pwd)"

if [[ ! -f "$K6_DIR/scenarios/$SCENARIO.js" ]]; then
  printf 'ERROR: scenario file not found: %s\n' "$K6_DIR/scenarios/$SCENARIO.js" >&2
  exit 2
fi

# Default to the compose service name. A host-network run (or a k6 binary on
# the host) can point at the published loopback port instead.
if [[ -z "${BASE_URL:-}" ]]; then
  if [[ "$K6_NETWORK" == "host" ]]; then
    BASE_URL="http://127.0.0.1:8080"
  else
    BASE_URL="http://nginx"
  fi
fi

# A single run id must be shared by all VUs; generate it here and pass it to
# the container so Prometheus can group every series from this run.
RUN_ID="${RUN_ID:-k6-$(date +%Y%m%d%H%M%S)-${RANDOM}}"

K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://prometheus:9090/api/v1/write}"
K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),max}"
K6_PROMETHEUS_RW_PUSH_INTERVAL="${K6_PROMETHEUS_RW_PUSH_INTERVAL:-5s}"

if ! docker image inspect "$K6_IMAGE" >/dev/null 2>&1; then
  printf 'ERROR: k6 image "%s" is not present locally. Pull it with:\n' "$K6_IMAGE" >&2
  printf '  docker pull %s\n' "$K6_IMAGE" >&2
  exit 2
fi

# Docker Desktop on Windows needs a Windows-style source path; Git Bash's
# cygpath -m produces one. Linux/macOS keep the absolute path unchanged.
host_mount_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

K6_MOUNT_DIR="$(host_mount_path "$K6_DIR")"

# Chaos-specific setup applies to ha-failover and db-failover; every other
# scenario keeps its original mounts, printed lines, forwarded environment and
# exit codes.
if [[ "$IS_CHAOS" == "1" ]]; then
  if [[ ! -d "$K6_SCENARIOS_DIR" ]]; then
    printf 'ERROR: chaos scenarios directory not found: %s\n' "$K6_SCENARIOS_DIR" >&2
    exit 2
  fi
  K6_SCENARIOS_DIR="$(cd "$K6_SCENARIOS_DIR" && pwd)"
  K6_SCENARIOS_MOUNT_DIR="$(host_mount_path "$K6_SCENARIOS_DIR")"
  # Inside the container the scenario JSON lives at the mount point unless the
  # caller pins another directory (e.g. a host k6 binary run).
  CHAOS_SCENARIO_DIR="${CHAOS_SCENARIO_DIR:-$K6_SCENARIOS_MOUNT}"
fi

if [[ -n "$K6_SUMMARY_EXPORT" && -z "$K6_REPORT_DIR" ]]; then
  printf 'ERROR: K6_SUMMARY_EXPORT requires K6_REPORT_DIR (the host directory mounted at %s).\n' \
    "$K6_REPORT_MOUNT" >&2
  exit 2
fi
if [[ -n "$K6_REPORT_DIR" ]]; then
  mkdir -p "$K6_REPORT_DIR"
  K6_REPORT_DIR="$(cd "$K6_REPORT_DIR" && pwd)"
  K6_REPORT_MOUNT_DIR="$(host_mount_path "$K6_REPORT_DIR")"
fi

# Config names are passed only when set; otherwise lib/config.js keeps the
# documented default. Values are forwarded explicitly so the container does
# not depend on shell exports.
CONFIG_VARS=(
  BASE_URL RUN_ID
  PRODUCT_ID SECKILL_PRODUCT_ID USER_PREFIX
  THINK_TIME HTTP_TIMEOUT
  ORDER_QUANTITY ORDER_KEY_PREFIX
  SECKILL_QUANTITY SECKILL_KEY_PREFIX SECKILL_EXPECTED_STATUSES
  LOGIN_PATH PRODUCTS_PATH ORDERS_PATH SECKILL_ORDERS_PATH
  SMOKE_VUS SMOKE_ITERATIONS SMOKE_DURATION
  LOAD_VUS LOAD_START_VUS LOAD_DURATION LOAD_RAMP_UP_FRACTION LOAD_RAMP_PEAK_FRACTION
  STRESS_STAGES
  SPIKE_BASELINE_VUS SPIKE_BASELINE_DURATION SPIKE_VUS SPIKE_RAMP_UP SPIKE_DURATION SPIKE_RAMP_DOWN
  SOAK_VUS SOAK_DURATION
  P95_MS P99_MS ERROR_RATE CHECKS_RATE
  SECKILL_POLL_ATTEMPTS SECKILL_POLL_INTERVAL
  SUMMARY_TREND_STATS SUMMARY_TIME_UNIT
  GRACEFUL_STOP GRACEFUL_RAMP_DOWN
  K6_PROMETHEUS_RW_SERVER_URL K6_PROMETHEUS_RW_TREND_STATS K6_PROMETHEUS_RW_PUSH_INTERVAL
)

# Chaos-controller variables (Task 22a). Forwarded only for the two chaos
# scenarios so the lab token and CHAOS_* settings never reach unrelated
# scenario containers. db-failover adds the DB_* target/TTL/window knobs.
if [[ "$IS_CHAOS" == "1" ]]; then
  CONFIG_VARS+=(
    CHAOS_CONTROLLER_URL LAB_TOKEN
    CHAOS_SCENARIO_FILE CHAOS_SCENARIO_DIR CHAOS_START_OFFSET CHAOS_HTTP_TIMEOUT CHAOS_TEARDOWN_TIMEOUT
    CHAOS_MAX_TTL CHAOS_TARGET_PATTERN CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN
    CHAOS_DURATION CHAOS_VUS DURATION
    DB_FAILOVER_TARGET DB_FAILOVER_TTL_SECONDS DB_FAILOVER_KILL_AT DB_FAILOVER_ERROR_WINDOW
    DB_FAILOVER_DURATION DB_FAILOVER_READ_VUS DB_FAILOVER_WRITE_VUS DB_FAILOVER_WRITE_INTERVAL
    DB_FAILOVER_READ_THINK_TIME DB_FAILOVER_WRITE_EXPECTED_STATUSES DB_FAILOVER_WRITE_MIN_SUCCESS_RATE
  )
fi

# Cache experiment (step 48). The token is needed for /lab/**; business reads
# still use BASE_URL (Nginx). The app URL list only covers the internal API.
if [[ "$IS_CACHE" == "1" ]]; then
  CONFIG_VARS+=(
    LAB_TOKEN CACHE_LAB_APP_URLS CACHE_LAB_SEED_START_ID CACHE_LAB_SEED_COUNT
    CACHE_LAB_MODES CACHE_LAB_MODE_DURATION CACHE_LAB_VUS CACHE_LAB_THINK_TIME
    CACHE_LAB_READ_EXPECTED_STATUSES CACHE_LAB_SAMPLE_ACTUATOR
    CACHE_LAB_SEED_PATH CACHE_LAB_MODE_PATH CACHE_LAB_STATE_PATH CACHE_LAB_ACTUATOR_PATH
  )
fi

# Additional names requested by a caller (bench.sh) so arbitrary scenario
# overrides reach the container without hardcoding them here. Names are
# validated: only plain environment variable identifiers are forwarded.
if [[ -n "${K6_FORWARD_ENV:-}" ]]; then
  IFS=',' read -r -a extra_names <<<"$K6_FORWARD_ENV"
  for name in "${extra_names[@]}"; do
    if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      printf 'ERROR: K6_FORWARD_ENV contains an invalid name "%s".\n' "$name" >&2
      exit 2
    fi
    CONFIG_VARS+=("$name")
  done
fi

docker_args=(
  run --rm
  --network "$K6_NETWORK"
  -v "$K6_MOUNT_DIR:/scripts:ro"
  -w /scripts
)
if [[ "$IS_CHAOS" == "1" ]]; then
  docker_args+=(-v "$K6_SCENARIOS_MOUNT_DIR:$K6_SCENARIOS_MOUNT:ro")
fi
if [[ -n "$K6_REPORT_DIR" ]]; then
  docker_args+=(-v "$K6_REPORT_MOUNT_DIR:$K6_REPORT_MOUNT")
fi
for name in "${CONFIG_VARS[@]}"; do
  value="${!name:-}"
  if [[ -n "$value" ]]; then
    docker_args+=(-e "$name=$value")
  fi
done

urlencode() {
  local value="$1"
  value="${value//%/%25}"
  value="${value// /%20}"
  value="${value//\"/%22}"
  value="${value//\{/%7B}"
  value="${value//\}/%7D}"
  value="${value//=/%3D}"
  printf '%s' "$value"
}

prom_query="k6_http_reqs_total{run_id=\"$RUN_ID\"}"
graph_url="${PROM_URL%/}/graph?g0.expr=$(urlencode "$prom_query")&g0.tab=0"

k6_flags=()
if [[ -n "$K6_SUMMARY_EXPORT" ]]; then
  k6_flags+=("--summary-export=$K6_SUMMARY_EXPORT")
fi

printf 'k6 scenario : %s\n' "$SCENARIO"
printf 'run_id      : %s\n' "$RUN_ID"
printf 'base_url    : %s\n' "$BASE_URL"
printf 'network     : %s\n' "$K6_NETWORK"
printf 'image       : %s\n' "$K6_IMAGE"
printf 'remote write: %s\n' "$K6_PROMETHEUS_RW_SERVER_URL"
if [[ "$IS_CHAOS" == "1" ]]; then
  printf 'scenarios   : %s -> %s\n' "$K6_SCENARIOS_DIR" "$K6_SCENARIOS_MOUNT"
  printf 'chaos ctl   : %s\n' "${CHAOS_CONTROLLER_URL:-http://chaos-controller:8081}"
  if [[ "$SCENARIO" == "db-failover" ]]; then
    printf 'db target   : %s (ttl %ss, kill at %s, error window %s)\n' \
      "${DB_FAILOVER_TARGET:-jl-mysql-2}" "${DB_FAILOVER_TTL_SECONDS:-45}" \
      "${DB_FAILOVER_KILL_AT:-20s}" "${DB_FAILOVER_ERROR_WINDOW:-30s}"
    printf 'db duration : %s (reads %s VU, writes %s VU every %s)\n' \
      "${DB_FAILOVER_DURATION:-${CHAOS_DURATION:-2m}}" \
      "${DB_FAILOVER_READ_VUS:-20}" "${DB_FAILOVER_WRITE_VUS:-1}" \
      "${DB_FAILOVER_WRITE_INTERVAL:-2s}"
  else
    printf 'chaos file  : %s (dir %s)\n' \
      "${CHAOS_SCENARIO_FILE:-ha-app-failover.json}" "$CHAOS_SCENARIO_DIR"
    printf 'chaos offset: %s\n' "${CHAOS_START_OFFSET:-0s}"
  fi
fi
if [[ "$IS_CACHE" == "1" ]]; then
  printf 'cache app   : %s\n' "${CACHE_LAB_APP_URLS:-http://app-1:8080}"
  printf 'cache modes : %s\n' \
    "${CACHE_LAB_MODES:-off,null-cache,bloom,mutex,logical-expire,ttl-jitter,l1-cache,db-fallback}"
  printf 'cache dwell : %s per mode (reads %s VU)\n' \
    "${CACHE_LAB_MODE_DURATION:-20s}" "${CACHE_LAB_VUS:-10}"
fi
if [[ -n "$K6_SUMMARY_EXPORT" ]]; then
  printf 'summary     : %s (host dir %s)\n' "$K6_SUMMARY_EXPORT" "$K6_REPORT_DIR"
fi
printf 'PromQL      : %s\n' "$prom_query"
printf 'Graph URL   : %s\n' "$graph_url"
printf '\n'

set +e
# MSYS_NO_PATHCONV keeps Git Bash from rewriting the container-side paths
# (e.g. -w /scripts) into Windows paths. It is ignored on Linux/macOS.
MSYS_NO_PATHCONV=1 docker "${docker_args[@]}" "$K6_IMAGE" \
  run -o experimental-prometheus-rw "${k6_flags[@]}" "$@" "scenarios/$SCENARIO.js"
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  printf '\nERROR: k6 exited with status %s (a failed threshold is expected to be non-zero).\n' "$status" >&2
fi
exit "$status"
