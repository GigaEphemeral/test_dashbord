#!/usr/bin/env bash
# Run one k6 scenario against the lab stack with Prometheus remote write.
#
#   bash code/lab/k6/run.sh smoke
#   LOAD_DURATION=2m bash code/lab/k6/run.sh load
#   K6_NETWORK=host BASE_URL=http://127.0.0.1:8080 bash code/lab/k6/run.sh smoke
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

if [[ $# -gt 0 && "$1" != -* ]]; then
  SCENARIO="$1"
  shift
fi

case "$SCENARIO" in
  smoke|load|stress|spike|soak) ;;
  *)
    printf 'ERROR: unknown scenario "%s" (expected smoke|load|stress|spike|soak).\n' "$SCENARIO" >&2
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
K6_MOUNT_DIR="$K6_DIR"
if command -v cygpath >/dev/null 2>&1; then
  K6_MOUNT_DIR="$(cygpath -m "$K6_DIR")"
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

docker_args=(
  run --rm
  --network "$K6_NETWORK"
  -v "$K6_MOUNT_DIR:/scripts:ro"
  -w /scripts
)
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

printf 'k6 scenario : %s\n' "$SCENARIO"
printf 'run_id      : %s\n' "$RUN_ID"
printf 'base_url    : %s\n' "$BASE_URL"
printf 'network     : %s\n' "$K6_NETWORK"
printf 'image       : %s\n' "$K6_IMAGE"
printf 'remote write: %s\n' "$K6_PROMETHEUS_RW_SERVER_URL"
printf 'PromQL      : %s\n' "$prom_query"
printf 'Graph URL   : %s\n' "$graph_url"
printf '\n'

set +e
# MSYS_NO_PATHCONV keeps Git Bash from rewriting the container-side paths
# (e.g. -w /scripts) into Windows paths. It is ignored on Linux/macOS.
MSYS_NO_PATHCONV=1 docker "${docker_args[@]}" "$K6_IMAGE" \
  run -o experimental-prometheus-rw "$@" "scenarios/$SCENARIO.js"
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  printf '\nERROR: k6 exited with status %s (a failed threshold is expected to be non-zero).\n' "$status" >&2
fi
exit "$status"
