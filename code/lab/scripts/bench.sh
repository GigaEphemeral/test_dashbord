#!/usr/bin/env bash
# Benchmark runner: preflight -> lab reset -> k6 -> verdict -> archive.
#
#   bash code/lab/scripts/bench.sh smoke
#   bash code/lab/scripts/bench.sh load -- LOAD_DURATION=1m
#   bash code/lab/scripts/bench.sh smoke --run-id demo-1 --no-reset
#   bash code/lab/scripts/bench.sh smoke -- ERROR_RATE=0     # negative test
#
# One run produces code/lab/k6/reports/<run_id>/:
#   summary.json  k6 --summary-export output
#   k6.log        full k6 stdout/stderr
#   manifest.json scenario/run/git/reset/thresholds/verdict
#   report.html   self-contained HTML rendered by bench-report.js
#   reset.log     reset-lab.sh output (when reset ran)
# and doc/process/benchmark/<date>-<scenario>-<run_id>.md.
#
# Exit code: k6's exit code when it is non-zero (a crossed threshold is 99),
# otherwise non-zero for an internal/report failure. The archive is written for
# both PASS and FAIL runs. The stack is never started by this script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

K6_DIR="${BENCH_K6_DIR:-$ROOT_DIR/code/lab/k6}"
K6_RUNNER="${BENCH_K6_RUNNER:-$K6_DIR/run.sh}"
REPORT_SCRIPT="${BENCH_REPORT_SCRIPT:-$SCRIPT_DIR/bench-report.js}"
RESET_SCRIPT="${BENCH_RESET_SCRIPT:-$SCRIPT_DIR/reset-lab.sh}"
REPORTS_DIR="${BENCH_REPORTS_DIR:-$K6_DIR/reports}"
DOC_DIR="${BENCH_DOC_DIR:-$ROOT_DIR/doc/process/benchmark}"
ENV_FILE="${BENCH_ENV_FILE:-$ROOT_DIR/code/lab/docker/.env}"
COMPOSE_PROJECT="${BENCH_COMPOSE_PROJECT:-java-learn-lab}"
DOCKER_DIR="${BENCH_DOCKER_DIR:-$ROOT_DIR/code/lab/docker}"

K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"
K6_NETWORK="${K6_NETWORK:-jl-net}"
K6_SUMMARY_EXPORT_CONTAINER="${BENCH_SUMMARY_EXPORT_PATH:-/reports/summary.json}"
PROM_URL="${PROM_URL:-http://127.0.0.1:8080/prom}"
GRAFANA_URL="${BENCH_GRAFANA_URL:-http://127.0.0.1:3000}"
GRAFANA_DASHBOARD_UID="${BENCH_GRAFANA_DASHBOARD_UID:-ha-d4}"
NGINX_SERVICE="${BENCH_NGINX_SERVICE:-nginx}"
PROMETHEUS_READY_URL="${BENCH_PROMETHEUS_READY_URL:-http://prometheus:9090/-/ready}"
HEALTH_URL="${BENCH_HEALTH_URL:-http://127.0.0.1:8080/health}"
READY_TIMEOUT_SECONDS="${BENCH_READY_TIMEOUT_SECONDS:-60}"
READY_INTERVAL_SECONDS="${BENCH_READY_INTERVAL_SECONDS:-3}"
HTTP_TIMEOUT="${BENCH_HTTP_TIMEOUT_SECONDS:-5}"
RESET_BASELINE_STOCK="${RESET_BASELINE_STOCK:-100}"
TRAFFIC_SERVICE="${BENCH_TRAFFIC_SERVICE:-traffic}"

usage() {
  cat <<'EOF'
Usage: bench.sh <smoke|load|stress|spike|soak> [options] [--] [k6 env overrides...]

Options:
  --run-id <id>   Archive id / k6 RUN_ID label (default: <timestamp>-<scenario>).
  --no-reset      Skip reset-lab.sh (baseline is recorded as not reset).
  -h, --help      Show this help.

Everything after "--" is exported as KEY=VALUE and forwarded to the k6
container. Examples:
  bench.sh load -- LOAD_DURATION=1m
  bench.sh smoke -- ERROR_RATE=0          # deliberately impossible threshold

Overrides after "--" must be k6 scenario variables. Internal names are
rejected (they would corrupt the archive or the runner): RUN_ID, SCENARIO,
PATH/HOME, K6_DIR/K6_IMAGE/K6_NETWORK/K6_REPORT_DIR/K6_REPORT_MOUNT/
K6_SUMMARY_EXPORT/K6_RUNNER/K6_FORWARD_ENV and any BENCH_*/RESET_* name.
Set BENCH_*/RESET_* variables in the environment before invoking bench.sh.

The stack must already be running; bench.sh never starts it.

Environment (defaults):
  BENCH_REPORTS_DIR=<repo>/code/lab/k6/reports
  BENCH_DOC_DIR=<repo>/doc/process/benchmark
  BENCH_GRAFANA_URL=http://127.0.0.1:3000
  BENCH_GRAFANA_DASHBOARD_UID=ha-d4
  BENCH_READY_TIMEOUT_SECONDS=60   BENCH_READY_INTERVAL_SECONDS=3
  K6_IMAGE=grafana/k6:1.3.0        K6_NETWORK=jl-net
  PROM_URL=http://127.0.0.1:8080/prom
  RESET_BASELINE_STOCK=100
  BENCH_TRAFFIC_SERVICE=traffic    BENCH_ALLOW_TRAFFIC=0 (1 = bench with the
                                   console background traffic still generating
                                   load)
EOF
}

SCENARIO=""
RUN_ID=""
DO_RESET=true
OVERRIDE_NAMES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: --run-id requires a value." >&2
        exit 2
      fi
      RUN_ID="$2"
      shift 2
      ;;
    --run-id=*)
      RUN_ID="${1#*=}"
      shift
      ;;
    --no-reset)
      DO_RESET=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      # Names bench.sh (or run.sh / reset-lab.sh) itself consumes; overriding
      # them via "--" would corrupt the archive, the mount or the reset
      # semantics, so they are rejected. Legitimate scenario overrides such as
      # LOAD_DURATION or ERROR_RATE are unaffected.
      reserved_override_re='^(PATH|HOME|IFS|ENV|BASH_ENV|RUN_ID|SCENARIO|K6_(DIR|RUNNER|IMAGE|NETWORK|REPORT_DIR|REPORT_MOUNT|SUMMARY_EXPORT|FORWARD_ENV)|BENCH_.*|RESET_.*)$'
      while [[ $# -gt 0 ]]; do
        override="$1"
        if [[ ! "$override" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
          printf 'ERROR: k6 override "%s" must be KEY=VALUE.\n' "$override" >&2
          exit 2
        fi
        override_name="${BASH_REMATCH[1]}"
        if [[ "$override_name" =~ $reserved_override_re ]]; then
          printf 'ERROR: override "%s" is reserved by bench.sh; pass only k6 scenario variables after "--".\n' \
            "$override_name" >&2
          exit 2
        fi
        export "$override"
        OVERRIDE_NAMES+=("$override_name")
        shift
      done
      ;;
    -*)
      printf 'ERROR: unknown option "%s".\n' "$1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$SCENARIO" ]]; then
        printf 'ERROR: unexpected argument "%s" (scenario already is "%s").\n' \
          "$1" "$SCENARIO" >&2
        exit 2
      fi
      SCENARIO="$1"
      shift
      ;;
  esac
done

if [[ -z "$SCENARIO" ]]; then
  echo "ERROR: a scenario is required." >&2
  usage >&2
  exit 2
fi
case "$SCENARIO" in
  smoke|load|stress|spike|soak) ;;
  *)
    printf 'ERROR: unknown scenario "%s" (expected smoke|load|stress|spike|soak).\n' \
      "$SCENARIO" >&2
    exit 2
    ;;
esac

if [[ -z "$RUN_ID" ]]; then
  RUN_ID="$(date +%Y%m%d-%H%M%S)-${SCENARIO}"
fi
if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  printf 'ERROR: run id "%s" may only contain letters, digits, dot, dash and underscore.\n' \
    "$RUN_ID" >&2
  exit 2
fi

if [[ ! -f "$K6_RUNNER" ]]; then
  printf 'ERROR: k6 runner not found: %s\n' "$K6_RUNNER" >&2
  exit 2
fi
if [[ ! -f "$K6_DIR/scenarios/$SCENARIO.js" ]]; then
  printf 'ERROR: scenario file not found: %s\n' "$K6_DIR/scenarios/$SCENARIO.js" >&2
  exit 2
fi
if [[ ! -f "$REPORT_SCRIPT" ]]; then
  printf 'ERROR: report generator not found: %s\n' "$REPORT_SCRIPT" >&2
  exit 2
fi
if $DO_RESET && [[ ! -f "$RESET_SCRIPT" ]]; then
  printf 'ERROR: reset script not found: %s\n' "$RESET_SCRIPT" >&2
  exit 2
fi
if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node is required for report generation (no npm dependencies)." >&2
  exit 2
fi
if ! docker image inspect "$K6_IMAGE" >/dev/null 2>&1; then
  printf 'ERROR: k6 image "%s" is not present locally. Pull it with:\n' "$K6_IMAGE" >&2
  printf '  docker pull %s\n' "$K6_IMAGE" >&2
  exit 2
fi

compose() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$DOCKER_DIR/compose.base.yml" \
    -f "$DOCKER_DIR/compose.dev.yml" \
    "$@"
}

# --- Preflight: stack must already be up and healthy ------------------------
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running or not reachable." >&2
  exit 2
fi
if [[ ! -f "$ENV_FILE" ]]; then
  printf 'ERROR: %s not found; start the stack first.\n' "$ENV_FILE" >&2
  exit 2
fi

compose_services="$(compose config --services 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$compose_services" ]]; then
  echo "ERROR: docker compose config --services returned nothing." >&2
  exit 2
fi

# Optional subset for future chaos runs; default is every compose service.
required_services_raw="${BENCH_REQUIRED_SERVICES:-$(tr '\n' ' ' <<<"$compose_services")}"
read -r -a required_services <<<"$required_services_raw"

service_ready() {
  local service="$1" container state health
  container="$(compose ps -q "$service" 2>/dev/null | head -n 1 || true)"
  if [[ -z "$container" ]]; then
    printf '%s=missing' "$service"
    return 1
  fi
  state="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "$container" 2>/dev/null || true)"
  printf '%s=%s/%s' "$service" "$state" "$health"
  [[ "$state" == "running" && ( "$health" == "healthy" || "$health" == "none" ) ]]
}

deadline=$(( SECONDS + READY_TIMEOUT_SECONDS ))
while :; do
  stack_ok=true
  status_line=""
  for service in "${required_services[@]}"; do
    if ! service_state="$(service_ready "$service")"; then
      stack_ok=false
    fi
    status_line+="$service_state "
  done
  if $stack_ok; then
    break
  fi
  if (( SECONDS >= deadline )); then
    printf 'ERROR: stack is not healthy after %ss:\n  %s\n' \
      "$READY_TIMEOUT_SECONDS" "${status_line% }" >&2
    compose ps >&2 || true
    echo "bench.sh never starts the stack; run code/lab/scripts/up.sh --profile dev." >&2
    exit 1
  fi
  sleep "$READY_INTERVAL_SECONDS"
done
printf 'Preflight: %d compose service(s) running/healthy\n' "${#required_services[@]}"

nginx_health="$(curl -fsS --max-time "$HTTP_TIMEOUT" "$HEALTH_URL" 2>/dev/null \
  | tr -d '\r\n' || true)"
if [[ "$nginx_health" != "ok" ]]; then
  printf 'ERROR: nginx health endpoint %s returned "%s".\n' "$HEALTH_URL" "$nginx_health" >&2
  exit 1
fi

# Checked through the pinned compose project (never a raw container name), so
# a same-named container from another project can never be exec'd here.
prom_ready="$(compose exec -T "$NGINX_SERVICE" wget -qO- -T "$HTTP_TIMEOUT" \
  "$PROMETHEUS_READY_URL" 2>/dev/null | tr -d '\r\n' || true)"
if [[ "$prom_ready" != *"Ready"* ]]; then
  printf 'ERROR: Prometheus %s is not ready ("%s").\n' "$PROMETHEUS_READY_URL" "$prom_ready" >&2
  exit 1
fi

# Console background traffic (M2-5) keeps generating load, which would make an
# archived run incomparable with the Task 19/20 baselines. Resolved through the
# compose project (profile-gated), never a raw container name.
traffic_id="$(compose --profile traffic ps -q "$TRAFFIC_SERVICE" 2>/dev/null | head -n 1 || true)"
traffic_state=""
if [[ -n "$traffic_id" ]]; then
  traffic_state="$(docker inspect -f '{{.State.Status}}' "$traffic_id" 2>/dev/null || true)"
fi
if [[ "$traffic_state" == "running" && "${BENCH_ALLOW_TRAFFIC:-0}" != "1" ]]; then
  echo "ERROR: the console background traffic ($TRAFFIC_SERVICE) is generating load." >&2
  echo "Stop it first (console traffic button, POST /lab-api/lab/traffic/stop," >&2
  echo "or 'docker stop $traffic_id') or set BENCH_ALLOW_TRAFFIC=1 to bench with it on." >&2
  exit 1
fi

# --- Archive layout ---------------------------------------------------------
mkdir -p "$REPORTS_DIR"
RUN_DIR="$REPORTS_DIR/$RUN_ID"
if [[ -d "$RUN_DIR" && -n "$(ls -A "$RUN_DIR" 2>/dev/null)" ]]; then
  printf 'ERROR: archive %s already exists; use a new --run-id.\n' "$RUN_DIR" >&2
  exit 2
fi
mkdir -p "$RUN_DIR"
RESET_LOG="$RUN_DIR/reset.log"

git_commit="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
git_branch="$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"

echo "Bench run"
echo "  scenario : $SCENARIO"
echo "  run_id   : $RUN_ID"
echo "  archive  : $RUN_DIR"
echo "  reset    : $DO_RESET (baseline stock $RESET_BASELINE_STOCK)"
echo

# --- Reset (default) --------------------------------------------------------
reset_executed=false
if $DO_RESET; then
  echo "Resetting lab data before the run..."
  set +e
  bash "$RESET_SCRIPT" --yes 2>&1 | tee "$RESET_LOG"
  reset_status=${PIPESTATUS[0]}
  set -e
  if [[ "$reset_status" -ne 0 ]]; then
    printf 'ERROR: reset-lab.sh exited %s; bench aborted before k6.\n' "$reset_status" >&2
    exit "$reset_status"
  fi
  reset_executed=true
  echo
fi

# --- k6 ---------------------------------------------------------------------
forward_env=""
if (( ${#OVERRIDE_NAMES[@]} > 0 )); then
  forward_env="$(IFS=,; printf '%s' "${OVERRIDE_NAMES[*]}")"
fi

started_epoch="$(date +%s)"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "Running k6 scenario $SCENARIO (run_id $RUN_ID)..."
set +e
(
  export RUN_ID SCENARIO
  export K6_REPORT_DIR="$RUN_DIR"
  export K6_SUMMARY_EXPORT="$K6_SUMMARY_EXPORT_CONTAINER"
  if [[ -n "$forward_env" ]]; then
    export K6_FORWARD_ENV="$forward_env"
  fi
  bash "$K6_RUNNER" "$SCENARIO"
) 2>&1 | tee "$RUN_DIR/k6.log"
k6_status=${PIPESTATUS[0]}
set -e

finished_epoch="$(date +%s)"
finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- Manifest / HTML / result doc ------------------------------------------
if [[ "$SCENARIO" == "stress" ]]; then
  thresholds_expected=false
else
  thresholds_expected=true
fi

report_args=(
  --summary "$RUN_DIR/summary.json"
  --log "$RUN_DIR/k6.log"
  --html "$RUN_DIR/report.html"
  --manifest "$RUN_DIR/manifest.json"
  --result-doc "$DOC_DIR/$(date +%Y-%m-%d)-${SCENARIO}-${RUN_ID}.md"
  --scenario "$SCENARIO"
  --run-id "$RUN_ID"
  --started-at "$started_at"
  --finished-at "$finished_at"
  --start-epoch "$started_epoch"
  --end-epoch "$finished_epoch"
  --k6-image "$K6_IMAGE"
  --k6-exit-code "$k6_status"
  --git-commit "$git_commit"
  --git-branch "$git_branch"
  --reset-executed "$reset_executed"
  --reset-baseline "$RESET_BASELINE_STOCK"
  --reset-log "$(basename "$RESET_LOG")"
  --grafana-url "$GRAFANA_URL"
  --grafana-dashboard "$GRAFANA_DASHBOARD_UID"
  --prom-url "$PROM_URL"
  --remote-write "${K6_PROMETHEUS_RW_SERVER_URL:-http://prometheus:9090/api/v1/write}"
  --report-dir "$RUN_DIR"
  --thresholds-expected "$thresholds_expected"
)
for override_name in "${OVERRIDE_NAMES[@]}"; do
  report_args+=(--env "${override_name}=${!override_name}")
done

set +e
report_output="$(node "$REPORT_SCRIPT" "${report_args[@]}" 2>&1)"
report_status=$?
set -e
if [[ -n "$report_output" ]]; then
  printf '%s\n' "$report_output"
fi

verdict="$(sed -n 's/^BENCH_VERDICT=//p' <<<"$report_output" | tail -n 1)"
result_doc="$(sed -n 's/^BENCH_RESULT_DOC=//p' <<<"$report_output" | tail -n 1)"
if [[ "$report_status" -ne 0 || -z "$verdict" ]]; then
  echo "ERROR: report generation failed (node exit $report_status)." >&2
  verdict="FAIL"
fi

echo
echo "BENCH RESULT"
echo "  scenario : $SCENARIO"
echo "  run_id   : $RUN_ID"
echo "  duration : $(( finished_epoch - started_epoch ))s"
echo "  k6 exit  : $k6_status"
echo "  verdict  : $verdict"
echo "  archive  : $RUN_DIR"
echo "  report   : $RUN_DIR/report.html"
if [[ -n "$result_doc" ]]; then
  echo "  result   : $result_doc"
fi

if [[ "$k6_status" -ne 0 ]]; then
  printf 'BENCH FAILED: k6 exited %s (threshold or runtime failure); archive kept.\n' \
    "$k6_status" >&2
  exit "$k6_status"
fi
if [[ "$verdict" != "PASS" ]]; then
  echo "BENCH FAILED: threshold verdict is $verdict; archive kept." >&2
  exit 1
fi
echo "BENCH PASSED."
