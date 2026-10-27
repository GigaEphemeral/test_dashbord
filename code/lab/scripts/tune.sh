#!/usr/bin/env bash
# Repeatable JVM tuning run for the lab app tier (design section 10.3 / D8).
#
#   bash code/lab/scripts/tune.sh --name xmx256 --flags "-Xmx256m"
#   bash code/lab/scripts/tune.sh --name xmx256 --flags "-Xmx256m" \
#     --scenario load --duration 60s
#   bash code/lab/scripts/tune.sh --restore
#
# What one run does:
#   1. records the flags currently applied to the app tier (container env);
#   2. recreates ONLY app-1/2/3 with APP_JAVA_TOOL_OPTIONS=<flags> (compose env
#      override, so .env is never rewritten), waits for healthy;
#   3. reloads Nginx so the recreated upstream addresses are re-resolved and
#      probes /health plus /api/products/1 through Nginx;
#   4. posts a best-effort Grafana annotation tagged tuning,<profile>;
#   5. optionally resets lab data and runs a short k6 scenario;
#   6. writes doc/process/Stage1/benchmark/tuning-<timestamp>-<profile>.md with the
#      before/after flags, run id and the PromQL needed to compare windows.
#
# The saved baseline is kept in $TUNE_STATE_FILE (default <repo>/tmp/...,
# gitignored) and can be re-applied with --restore. On a failed apply the ERR
# trap tries to restore the previous flags before exiting.
#
# Every path, URL, timeout and retry is environment-overridable; see the help
# text. Only containers of the pinned compose project are touched. The stack
# must already be running and must use the dev overlay (not chaos-proxy).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

DOCKER_DIR="${TUNE_DOCKER_DIR:-$ROOT_DIR/code/lab/docker}"
ENV_FILE="${TUNE_ENV_FILE:-$DOCKER_DIR/.env}"
K6_RUNNER="${TUNE_K6_RUNNER:-$ROOT_DIR/code/lab/k6/run.sh}"
RESET_SCRIPT="${TUNE_RESET_SCRIPT:-$SCRIPT_DIR/reset-lab.sh}"
# Stage2 B0-08 slice A: one `docker inspect` pass over the rebuilt app tier
# records imageTag + imageId in the result note (minimal loop: no provenance,
# no base digest pin). The rebuild itself stays explicitly --force-recreate.
IMAGE_IDENTITY_SCRIPT="${TUNE_IMAGE_IDENTITY_SCRIPT:-$SCRIPT_DIR/image-identity.sh}"
# Empty means "the app tier this script just recreated" (jl-<service>), resolved
# in record_image_identity() where APP_SERVICES is already read.
IMAGE_IDENTITY_CONTAINERS="${TUNE_IMAGE_IDENTITY_CONTAINERS:-}"
DOC_DIR="${TUNE_DOC_DIR:-$ROOT_DIR/doc/process/Stage1/benchmark}"
STATE_FILE="${TUNE_STATE_FILE:-$ROOT_DIR/tmp/tuning-state.env}"
COMPOSE_PROJECT="${TUNE_COMPOSE_PROJECT:-java-learn-lab}"
NGINX_CONTAINER="${TUNE_NGINX_CONTAINER:-jl-nginx}"
TOXIPROXY_CONTAINER="${TUNE_TOXIPROXY_CONTAINER:-jl-toxiproxy}"

APP_SERVICES_RAW="${TUNE_APP_SERVICES:-app-1 app-2 app-3}"
read -r -a APP_SERVICES <<<"$APP_SERVICES_RAW"
APP_PROBE_SERVICE="${TUNE_APP_PROBE_SERVICE:-app-1}"
DEFAULT_FLAGS="${TUNE_DEFAULT_FLAGS:--Xmx512m}"

GRAFANA_URL="${TUNE_GRAFANA_URL:-${GRAFANA_URL:-http://127.0.0.1:3000}}"
NGINX_HEALTH_URL="${TUNE_NGINX_HEALTH_URL:-http://127.0.0.1:8080/health}"
NGINX_API_PROBE_URL="${TUNE_NGINX_API_PROBE_URL:-http://127.0.0.1:8080/api/products/1}"
HEALTH_TIMEOUT_SECONDS="${TUNE_HEALTH_TIMEOUT_SECONDS:-240}"
HEALTH_INTERVAL_SECONDS="${TUNE_HEALTH_INTERVAL_SECONDS:-3}"
HTTP_READY_TIMEOUT_SECONDS="${TUNE_HTTP_READY_TIMEOUT_SECONDS:-45}"
HTTP_READY_INTERVAL_SECONDS="${TUNE_HTTP_READY_INTERVAL_SECONDS:-2}"
HTTP_TIMEOUT_SECONDS="${TUNE_HTTP_TIMEOUT_SECONDS:-5}"
ANNOTATION_TIMEOUT_SECONDS="${TUNE_ANNOTATION_TIMEOUT_SECONDS:-5}"
DEFAULT_DURATION="${TUNE_DEFAULT_DURATION:-60s}"

OPERATOR="${TUNE_OPERATOR:-$(git -C "$ROOT_DIR" config user.name 2>/dev/null || true)}"
OPERATOR="${OPERATOR:-${USER:-${USERNAME:-unknown}}}"
OPERATOR="$(printf '%s' "$OPERATOR" | tr -d '\r\n')"

MODE="run"
NAME=""
FLAGS=""
SCENARIO=""
DURATION="$DEFAULT_DURATION"
DO_RESET=true

BASELINE_FLAGS=""
PREVIOUS_FLAGS=""
STATE_PROFILE=""
STATE_UPDATED=""

APPLY_IN_PROGRESS=0
RESTORE_ON_ERR_FLAGS=""
TUNE_TMP_FILES=()

# Image identity record (B0-08 slice A); filled by record_image_identity().
IMAGE_IDENTITY_TABLE=""
IMAGE_IDENTITY_NOTE=""
IMAGE_IDENTITY_FILE=""

usage() {
  cat <<'EOF'
Usage: tune.sh --name <profile> --flags "<jvm flags>" [options]
       tune.sh --restore

Options:
  --name <profile>     Result-note / annotation profile name. Allowed:
                       letters, digits, dot, dash, underscore.
  --flags "<flags>"    JAVA_TOOL_OPTIONS for the app tier. Every token must
                       start with "-"; only letters, digits, space and
                       . _ = : + , - are accepted. Agent/exec flags
                       (-javaagent, -agentlib, -agentpath, -XX:OnError,
                       -XX:OnOutOfMemoryError) are rejected.
  --scenario <name>    Optional short k6 run after the change: smoke|load.
  --duration <n>s|m    k6 duration (default: 60s). load -> LOAD_DURATION,
                       smoke -> SMOKE_DURATION.
  --no-reset           Skip reset-lab.sh before k6 (default: reset data).
  --restore            Re-apply the saved baseline flags and stop.
  -h, --help           Show this help.

Behavior:
  * Only app-1/app-2/app-3 are recreated (compose --no-deps
    --force-recreate) with APP_JAVA_TOOL_OPTIONS passed as a compose
    environment override. .env is never edited.
  * Right after the recreation the imageTag + imageId of those containers are
    recorded once (image-identity.sh, Stage2 B0-08 minimal loop) into the
    result note. A record failure only WARNs.
  * The flags currently in the container are saved before the change; the
    first saved baseline is kept until --restore re-applies it.
  * A best-effort Grafana annotation (tags tuning,<profile>, and
    tuning,restore for --restore) is posted; failures only WARN.
  * Result note: doc/process/Stage1/benchmark/tuning-<timestamp>-<profile>.md.

Exit code: 0 on success; k6's status when k6 ran and failed; 2 on a usage or
validation error; 1 on a runtime/preflight error.

Environment (all optional):
  TUNE_ENV_FILE, TUNE_DOCKER_DIR, TUNE_STATE_FILE, TUNE_DOC_DIR
  TUNE_K6_RUNNER, TUNE_RESET_SCRIPT, TUNE_DEFAULT_FLAGS
  TUNE_HEALTH_TIMEOUT_SECONDS=240   TUNE_HEALTH_INTERVAL_SECONDS=3
  TUNE_HTTP_READY_TIMEOUT_SECONDS=45 TUNE_HTTP_READY_INTERVAL_SECONDS=2
  TUNE_HTTP_TIMEOUT_SECONDS=5       TUNE_ANNOTATION_TIMEOUT_SECONDS=5
  TUNE_GRAFANA_URL / GRAFANA_URL    TUNE_ANNOTATE=1 (0 disables)
  GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD (else read from .env)
  TUNE_OPERATOR, TUNE_DEFAULT_DURATION=60s
  TUNE_APP_SERVICES="app-1 app-2 app-3" TUNE_APP_PROBE_SERVICE=app-1
  TUNE_IMAGE_IDENTITY_SCRIPT=code/lab/scripts/image-identity.sh
  TUNE_IMAGE_IDENTITY_CONTAINERS="" (empty = jl-<each app service>)
  TUNE_NGINX_CONTAINER=jl-nginx  TUNE_NGINX_HEALTH_URL
  TUNE_NGINX_API_PROBE_URL       TUNE_TOXIPROXY_CONTAINER=jl-toxiproxy
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

warn() {
  printf 'WARN: %s\n' "$*" >&2
}

cleanup() {
  local file
  for file in "${TUNE_TMP_FILES[@]:-}"; do
    if [[ -n "$file" ]]; then
      rm -f "$file"
    fi
  done
}
trap cleanup EXIT

validate_flags() {
  local flags="$1" token
  local -a tokens=()
  local allowed_re='^[A-Za-z0-9._=:+, -]+$'
  if [[ -z "$flags" ]]; then
    die "flags must not be empty."
  fi
  if [[ "$flags" == *$'\n'* || "$flags" == *$'\r'* || "$flags" == *$'\t'* ]]; then
    die "flags must be a single line without tabs."
  fi
  if [[ ! "$flags" =~ $allowed_re ]]; then
    die "flags may only contain letters, digits, space and . _ = : + , - (got: $flags)."
  fi
  read -r -a tokens <<<"$flags"
  if (( ${#tokens[@]} == 0 )); then
    die "flags must contain at least one JVM option."
  fi
  for token in "${tokens[@]}"; do
    if [[ "$token" != -* ]]; then
      die "every flag token must start with '-': $token"
    fi
    case "$token" in
      -javaagent:*|-agentlib:*|-agentpath:*|-XX:OnError=*|-XX:OnOutOfMemoryError=*)
        die "agent/exec flags are not allowed by tune.sh: $token"
        ;;
    esac
  done
}

validate_duration() {
  [[ "$1" =~ ^[0-9]+[sm]$ ]] || die "duration must look like 30s or 2m (got: $1)."
}

read_env_file_key() {
  local key="$1" line value=""
  if [[ ! -f "$ENV_FILE" ]]; then
    return 1
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" == "$key="* ]]; then
      value="${line#*=}"
    fi
  done <"$ENV_FILE"
  if [[ -z "$value" ]]; then
    return 1
  fi
  if [[ ${#value} -ge 2 && "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
    value="${value:1:${#value}-2}"
  elif [[ ${#value} -ge 2 && "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

compose() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$DOCKER_DIR/compose.base.yml" \
    -f "$DOCKER_DIR/compose.dev.yml" \
    "$@"
}

load_state() {
  local line key value
  BASELINE_FLAGS=""
  PREVIOUS_FLAGS=""
  STATE_PROFILE=""
  STATE_UPDATED=""
  if [[ ! -f "$STATE_FILE" ]]; then
    return 0
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ -z "$line" || "$line" == \#* ]]; then
      continue
    fi
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      BASELINE_FLAGS) BASELINE_FLAGS="$value" ;;
      PREVIOUS_FLAGS) PREVIOUS_FLAGS="$value" ;;
      PROFILE) STATE_PROFILE="$value" ;;
      UPDATED) STATE_UPDATED="$value" ;;
    esac
  done <"$STATE_FILE"
}

save_state() {
  local dir tmp
  dir="$(dirname "$STATE_FILE")"
  mkdir -p "$dir"
  tmp="$STATE_FILE.tmp.$$"
  {
    printf '# tune.sh state; do not edit while a run is in progress.\n'
    printf 'BASELINE_FLAGS=%s\n' "$BASELINE_FLAGS"
    printf 'PREVIOUS_FLAGS=%s\n' "$PREVIOUS_FLAGS"
    printf 'PROFILE=%s\n' "$STATE_PROFILE"
    printf 'UPDATED=%s\n' "$STATE_UPDATED"
  } >"$tmp"
  mv -f "$tmp" "$STATE_FILE"
}

current_flags() {
  local cid env_line flags=""
  cid="$(compose ps -q "$APP_PROBE_SERVICE" 2>/dev/null | head -n 1 || true)"
  if [[ -n "$cid" ]]; then
    env_line="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$cid" 2>/dev/null \
      | { grep '^JAVA_TOOL_OPTIONS=' || true; } | head -n 1 || true)"
    if [[ -n "$env_line" ]]; then
      printf '%s' "${env_line#JAVA_TOOL_OPTIONS=}"
      return 0
    fi
  fi
  flags="$(read_env_file_key APP_JAVA_TOOL_OPTIONS || true)"
  if [[ -n "$flags" ]]; then
    printf '%s' "$flags"
  else
    printf '%s' "$DEFAULT_FLAGS"
  fi
}

wait_apps_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local svc cid state health status all_ok
  while :; do
    all_ok=true
    status=""
    for svc in "${APP_SERVICES[@]}"; do
      cid="$(compose ps -q "$svc" 2>/dev/null | head -n 1 || true)"
      if [[ -z "$cid" ]]; then
        all_ok=false
        status+="$svc=missing "
        continue
      fi
      state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || true)"
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || true)"
      status+="$svc=$state/$health "
      if [[ "$state" != "running" || "$health" != "healthy" ]]; then
        all_ok=false
      fi
    done
    if $all_ok; then
      return 0
    fi
    if (( SECONDS >= deadline )); then
      printf 'ERROR: app tier not healthy after %ss: %s\n' \
        "$HEALTH_TIMEOUT_SECONDS" "${status% }" >&2
      compose ps >&2 || true
      return 1
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done
}

wait_http_ready() {
  local deadline=$((SECONDS + HTTP_READY_TIMEOUT_SECONDS))
  while :; do
    if curl -fsS -m "$HTTP_TIMEOUT_SECONDS" "$NGINX_HEALTH_URL" 2>/dev/null | grep -q '^ok'; then
      if curl -fsS -m "$HTTP_TIMEOUT_SECONDS" -o /dev/null "$NGINX_API_PROBE_URL" 2>/dev/null; then
        return 0
      fi
    fi
    if (( SECONDS >= deadline )); then
      printf 'ERROR: Nginx did not serve /health and %s within %ss.\n' \
        "$NGINX_API_PROBE_URL" "$HTTP_READY_TIMEOUT_SECONDS" >&2
      return 1
    fi
    sleep "$HTTP_READY_INTERVAL_SECONDS"
  done
}

reload_nginx() {
  if ! docker inspect "$NGINX_CONTAINER" >/dev/null 2>&1; then
    warn "nginx container $NGINX_CONTAINER not found; skipping reload."
    return 0
  fi
  if ! docker exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1; then
    warn "nginx -t failed inside $NGINX_CONTAINER; not reloading."
    return 0
  fi
  if ! docker exec "$NGINX_CONTAINER" nginx -s reload >/dev/null 2>&1; then
    warn "nginx reload failed inside $NGINX_CONTAINER."
    return 0
  fi
  echo "Nginx reloaded (upstream names re-resolved)."
}

apply_flags() {
  local flags="$1" profile="$2" previous="$3" reason="$4"
  validate_flags "$flags"
  APPLY_IN_PROGRESS=1
  RESTORE_ON_ERR_FLAGS="$previous"
  printf 'Applying JVM flags (%s): %s\n' "$reason" "$flags"
  APP_JAVA_TOOL_OPTIONS="$flags" compose up -d --no-deps --force-recreate \
    --wait --wait-timeout "$HEALTH_TIMEOUT_SECONDS" "${APP_SERVICES[@]}" || return 1
  wait_apps_healthy || return 1
  reload_nginx || true
  wait_http_ready || return 1
  APPLY_IN_PROGRESS=0
  RESTORE_ON_ERR_FLAGS=""
}

# Record the imageTag + imageId of the containers just recreated (B0-08 slice
# A, minimal loop). Best effort: no Docker or a missing helper only WARNs and
# leaves the note saying "not recorded" - a tuning run is never lost over it.
record_image_identity() {
  IMAGE_IDENTITY_TABLE=""
  IMAGE_IDENTITY_NOTE=""
  IMAGE_IDENTITY_FILE=""
  local containers="${IMAGE_IDENTITY_CONTAINERS:-}"
  if [[ -z "$containers" ]]; then
    containers="$(printf 'jl-%s ' "${APP_SERVICES[@]}")"
  fi
  if [[ ! -f "$IMAGE_IDENTITY_SCRIPT" ]]; then
    warn "image identity script not found: $IMAGE_IDENTITY_SCRIPT; imageTag/imageId not recorded."
    IMAGE_IDENTITY_NOTE="未记录（image-identity.sh 缺失）"
    return 0
  fi
  local out output rc=0
  out="$(mktemp "${TMPDIR:-/tmp}/tune-image-identity.XXXXXX")"
  TUNE_TMP_FILES+=("$out")
  output="$(bash "$IMAGE_IDENTITY_SCRIPT" --out "$out" --containers "$containers" 2>&1)" || rc=$?
  if [[ -n "$output" ]]; then
    printf '%s\n' "$output"
  fi
  if (( rc != 0 )) || [[ ! -s "$out" ]]; then
    warn "image identity record failed (exit $rc); imageTag/imageId are recorded as unavailable."
    IMAGE_IDENTITY_NOTE="未记录（docker 不可达或记录失败，exit $rc）"
    return 0
  fi
  IMAGE_IDENTITY_FILE="$out"
  # One line per container in the hand-written JSON: container/imageTag/imageId/state.
  IMAGE_IDENTITY_TABLE="$(sed -n \
    's/^    "\([^"]*\)": {.*"imageTag": "\([^"]*\)".*"imageId": "\([^"]*\)".*"state": "\([^"]*\)".*/| `\1` | `\2` | `\3` | `\4` |/p' \
    "$out")"
  if [[ -z "$IMAGE_IDENTITY_TABLE" ]]; then
    warn "image identity record has no readable container entry."
    IMAGE_IDENTITY_NOTE="未记录（记录中没有可读的容器项）"
  fi
  return 0
}

annotate() {
  local profile="$1" text="$2" user pass payload status config
  ANNOTATION_STATUS="skipped"
  if [[ "${TUNE_ANNOTATE:-1}" != "1" ]]; then
    ANNOTATION_STATUS="disabled"
    return 0
  fi
  user="${GRAFANA_ADMIN_USER:-$(read_env_file_key GRAFANA_ADMIN_USER || true)}"
  pass="${GRAFANA_ADMIN_PASSWORD:-$(read_env_file_key GRAFANA_ADMIN_PASSWORD || true)}"
  if [[ -z "$user" ]]; then
    warn "GRAFANA_ADMIN_USER is not set and missing from $ENV_FILE; skipping annotation."
    ANNOTATION_STATUS="no-credentials"
    return 0
  fi
  text="$(printf '%s' "$text" | tr -d '\r\n')"
  text="${text//\\/\\\\}"
  text="${text//\"/\\\"}"
  payload="$(printf '{"tags":["tuning","%s"],"text":"%s","time":%s}' \
    "$profile" "$text" "$(( $(date +%s) * 1000 ))")"
  config="$(mktemp "${TMPDIR:-/tmp}/tune-grafana.XXXXXX")"
  TUNE_TMP_FILES+=("$config")
  chmod 600 "$config" 2>/dev/null || true
  {
    printf 'user = "%s"\n' "$(printf '%s:%s' "$user" "$pass" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  } >"$config"
  status="$(curl -sS -m "$ANNOTATION_TIMEOUT_SECONDS" --config "$config" -o /dev/null \
    -w '%{http_code}' -H 'Content-Type: application/json' \
    --data-binary "$payload" "$GRAFANA_URL/api/annotations" 2>/dev/null || true)"
  rm -f "$config"
  if [[ "$status" =~ ^2 ]]; then
    ANNOTATION_STATUS="posted (HTTP $status)"
    echo "Grafana annotation posted (tags tuning,$profile)."
  else
    warn "Grafana annotation failed (status ${status:-unreachable}); continuing."
    ANNOTATION_STATUS="failed (${status:-unreachable})"
  fi
}

on_error() {
  local rc=$?
  trap - ERR
  if [[ "$APPLY_IN_PROGRESS" == "1" && -n "$RESTORE_ON_ERR_FLAGS" ]]; then
    printf 'ERROR: applying flags failed (exit %s); trying to restore previous flags: %s\n' \
      "$rc" "$RESTORE_ON_ERR_FLAGS" >&2
    APPLY_IN_PROGRESS=0
    if apply_flags "$RESTORE_ON_ERR_FLAGS" restore "$RESTORE_ON_ERR_FLAGS" "error recovery"; then
      printf 'NOTE: previous flags restored; the app tier is back on the previous configuration.\n' >&2
    else
      printf 'ERROR: automatic restore FAILED. Recover with: bash %s --restore\n' "$0" >&2
    fi
  fi
  exit "$rc"
}
trap on_error ERR

# --- Argument parsing -------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)
      [[ $# -ge 2 ]] || die "--name requires a value."
      NAME="$2"
      shift 2
      ;;
    --name=*)
      NAME="${1#*=}"
      shift
      ;;
    --flags)
      [[ $# -ge 2 ]] || die "--flags requires a value."
      FLAGS="$2"
      shift 2
      ;;
    --flags=*)
      FLAGS="${1#*=}"
      shift
      ;;
    --scenario)
      [[ $# -ge 2 ]] || die "--scenario requires a value."
      SCENARIO="$2"
      shift 2
      ;;
    --scenario=*)
      SCENARIO="${1#*=}"
      shift
      ;;
    --duration)
      [[ $# -ge 2 ]] || die "--duration requires a value."
      DURATION="$2"
      shift 2
      ;;
    --duration=*)
      DURATION="${1#*=}"
      shift
      ;;
    --no-reset)
      DO_RESET=false
      shift
      ;;
    --restore)
      MODE="restore"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      die "unexpected argument: $1"
      ;;
  esac
done

if [[ "$MODE" == "restore" ]]; then
  if [[ -n "$NAME" || -n "$FLAGS" || -n "$SCENARIO" ]]; then
    die "--restore does not accept --name/--flags/--scenario."
  fi
else
  [[ -n "$NAME" ]] || die "--name is required (see --help)."
  [[ -n "$FLAGS" ]] || die "--flags is required (see --help)."
  validate_flags "$FLAGS"
  [[ "$NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || die "profile name may only contain letters, digits, dot, dash and underscore."
  if [[ -n "$SCENARIO" ]]; then
    case "$SCENARIO" in
      smoke|load) ;;
      *) die "unknown scenario \"$SCENARIO\" (tune.sh supports smoke|load)." ;;
    esac
    validate_duration "$DURATION"
  fi
fi

# --- Preflight --------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker is not in PATH."
docker info >/dev/null 2>&1 || die "Docker is not running or not reachable."
[[ -f "$ENV_FILE" ]] || die "$ENV_FILE not found; start the stack with up.sh first."
[[ -d "$DOCKER_DIR" ]] || die "compose directory not found: $DOCKER_DIR"

compose_services="$(compose config --services 2>/dev/null | tr -d '\r' || true)"
[[ -n "$compose_services" ]] || die "docker compose config --services returned nothing."

for svc in "${APP_SERVICES[@]}"; do
  if ! grep -qx "$svc" <<<"$compose_services"; then
    die "app service \"$svc\" is not part of the compose project."
  fi
done

if [[ "$(docker inspect -f '{{.State.Status}}' "$TOXIPROXY_CONTAINER" 2>/dev/null || true)" == "running" ]]; then
  die "the chaos-proxy overlay is active ($TOXIPROXY_CONTAINER is running); tune.sh only supports the dev overlay."
fi

for svc in "${APP_SERVICES[@]}"; do
  if [[ -z "$(compose ps -q "$svc" 2>/dev/null | head -n 1 || true)" ]]; then
    die "app service \"$svc\" has no container; start the stack with up.sh --profile dev."
  fi
done

if [[ -n "$SCENARIO" ]]; then
  [[ -f "$K6_RUNNER" ]] || die "k6 runner not found: $K6_RUNNER"
  if $DO_RESET; then
    [[ -f "$RESET_SCRIPT" ]] || die "reset script not found: $RESET_SCRIPT"
  fi
fi

load_state

# --- Restore mode -----------------------------------------------------------
if [[ "$MODE" == "restore" ]]; then
  if [[ -z "$BASELINE_FLAGS" ]]; then
    warn "no saved baseline in $STATE_FILE."
    echo "Current app flags: $(current_flags)"
    echo "Nothing to restore."
    exit 0
  fi
  validate_flags "$BASELINE_FLAGS"
  current="$(current_flags)"
  echo "Saved baseline : $BASELINE_FLAGS"
  echo "Current flags  : $current"
  if [[ "$current" == "$BASELINE_FLAGS" ]]; then
    echo "App tier already uses the baseline flags; no recreation needed."
    wait_apps_healthy
    wait_http_ready
    exit 0
  fi
  apply_flags "$BASELINE_FLAGS" "restore" "$current" "restore baseline"
  record_image_identity
  PREVIOUS_FLAGS="$current"
  STATE_PROFILE="restore"
  STATE_UPDATED="$(date +%Y-%m-%dT%H:%M:%S)"
  save_state
  annotate "restore" "tuning restore: flags=$BASELINE_FLAGS previous=$current operator=$OPERATOR"
  echo "Restored app tier to baseline: $BASELINE_FLAGS"
  echo "Baseline stays in $STATE_FILE; rerun --restore anytime."
  exit 0
fi

# --- Tuning run -------------------------------------------------------------
CURRENT_FLAGS="$(current_flags)"
if [[ -z "$BASELINE_FLAGS" ]]; then
  BASELINE_FLAGS="$CURRENT_FLAGS"
fi
validate_flags "$BASELINE_FLAGS"

STAMP="$(date +%Y%m%d-%H%M%S)"
STARTED_AT="$(date +%Y-%m-%dT%H:%M:%S)"
START_EPOCH="$(date +%s)"
RUN_ID=""
if [[ -n "$SCENARIO" ]]; then
  RUN_ID="tune-$STAMP-$NAME"
fi

echo "Profile        : $NAME"
echo "Flags          : $FLAGS"
echo "Current flags  : $CURRENT_FLAGS"
echo "Saved baseline : $BASELINE_FLAGS"
echo "Operator       : $OPERATOR"
if [[ -n "$SCENARIO" ]]; then
  echo "k6             : $SCENARIO $DURATION (run_id=$RUN_ID)"
fi

PREVIOUS_FLAGS="$CURRENT_FLAGS"
STATE_PROFILE="$NAME"
STATE_UPDATED="$STARTED_AT"
save_state

if [[ "$CURRENT_FLAGS" == "$FLAGS" ]]; then
  echo "Flags already applied; skipping container recreation."
  wait_apps_healthy
  wait_http_ready
else
  apply_flags "$FLAGS" "$NAME" "$CURRENT_FLAGS" "profile $NAME"
fi
# The app tier exists in either branch, so the tag/id record always runs.
record_image_identity

annotate "$NAME" \
  "tuning $NAME: flags=$FLAGS previous=$CURRENT_FLAGS baseline=$BASELINE_FLAGS operator=$OPERATOR scenario=${SCENARIO:-none} duration=${SCENARIO:+$DURATION} run_id=${RUN_ID:-none}"

K6_STATUS=0
if [[ -n "$SCENARIO" ]]; then
  if $DO_RESET; then
    echo "Resetting lab data before k6..."
    if ! bash "$RESET_SCRIPT" --yes; then
      echo "ERROR: reset-lab.sh failed; aborting before k6." >&2
      exit 1
    fi
    wait_apps_healthy || exit 1
    wait_http_ready || exit 1
  fi
  case "$SCENARIO" in
    load) DURATION_VAR="LOAD_DURATION" ;;
    smoke) DURATION_VAR="SMOKE_DURATION" ;;
  esac
  echo "Running k6 $SCENARIO for $DURATION..."
  set +e
  (
    export "$DURATION_VAR=$DURATION"
    export RUN_ID="$RUN_ID"
    exec bash "$K6_RUNNER" "$SCENARIO"
  )
  K6_STATUS=$?
  set -e
  if (( K6_STATUS != 0 )); then
    printf 'WARN: k6 exited %s (threshold failure is still recorded in the note).\n' "$K6_STATUS" >&2
  fi
fi

END_EPOCH="$(date +%s)"
ENDED_AT="$(date +%Y-%m-%dT%H:%M:%S)"
BRANCH="$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
if [[ -n "$SCENARIO" ]]; then
  if $DO_RESET; then RESET_NOTE="yes"; else RESET_NOTE="no (--no-reset)"; fi
else
  RESET_NOTE="n/a (no k6)"
fi
APP_SERVICES_STR="${APP_SERVICES[*]}"
NOTE="$DOC_DIR/tuning-$STAMP-$NAME.md"
mkdir -p "$DOC_DIR"
FROM_MS=$((START_EPOCH * 1000))
TO_MS=$((END_EPOCH * 1000))

if [[ -n "$IMAGE_IDENTITY_TABLE" ]]; then
  IMAGE_IDENTITY_BLOCK="| 容器 | imageTag | imageId | 容器状态 |
|---|---|---|---|
$IMAGE_IDENTITY_TABLE

- 取数：\`docker inspect --format '{{.Image}}'\` + \`docker image inspect --format '{{.RepoTags}}'\`，
  重建（显式 \`--force-recreate\`）之后记录一次。
- 口径：**只记录 tag/id**，不校验镜像来源、不归档构建日志、不锁 base digest（完整溯源在 Future）。
- 原始记录：\`$IMAGE_IDENTITY_FILE\`"
else
  IMAGE_IDENTITY_BLOCK="- ${IMAGE_IDENTITY_NOTE:-未记录（原因未知）}"
fi

cat >"$NOTE" <<EOF
# JVM 调优实验 · $NAME

> 日期：$STARTED_AT（结束 $ENDED_AT）
> 操作者：$OPERATOR
> 分支 / commit：$BRANCH / $COMMIT
> 参数：$FLAGS
> 变更前参数：$CURRENT_FLAGS
> 保存的基线：$BASELINE_FLAGS
> k6 场景：${SCENARIO:-未运行}
> k6 时长：${SCENARIO:+$DURATION}
> run_id：${RUN_ID:-n/a}
> 数据重置：$RESET_NOTE
> Grafana annotation：$ANNOTATION_STATUS
> 窗口（epoch 秒）：$START_EPOCH → $END_EPOCH

## 本轮动作

- 只重建 app 容器（$APP_SERVICES_STR；本轮参数与当前一致时跳过重建），其它服务未动。
- 使用 APP_JAVA_TOOL_OPTIONS="$FLAGS" 作为 compose 环境覆盖，未修改 \`.env\`。
- 重建后等待 healthy，执行 Nginx reload，并探测 \`/health\` 与 \`/api/products/1\`。
- 还原：\`bash code/lab/scripts/tune.sh --restore\`（基线 $BASELINE_FLAGS）。
- D8 面板：$GRAFANA_URL/d/ha-d8?from=$FROM_MS&to=$TO_MS&var-instance=All

## 镜像身份（最小记录，B0-08）

$IMAGE_IDENTITY_BLOCK

## 对比口径

一次只改一个参数；同一 k6 场景、同一时长、同一 VU 曲线。以 annotation 时间为界，
取前后等长窗口（建议 ≥ 45s，并跳过重建后前 30s 的启动抖动）。堆上限看
\`jvm_memory_max_bytes\` 的阶跃；GC 只有 sum/count/max，没有直方图，P99 用
\`max_over_time(jvm_gc_pause_seconds_max[...])\` 作为最长单次暂停的代理。

EOF

cat >>"$NOTE" <<'PROMQL'
## 对比 PromQL（Prometheus / Explore；窗口按 annotation 前后等长截取）

```promql
# 堆使用 / 提交 / 上限（上限每池一条，max by 取 old-gen 池）
sum by (instance) (jvm_memory_used_bytes{job="seckill-app", area="heap"})
sum by (instance) (jvm_memory_committed_bytes{job="seckill-app", area="heap"})
max by (instance) (jvm_memory_max_bytes{job="seckill-app", area="heap"})

# GC 暂停速率与最长单次暂停（P99 代理）
sum by (instance) (rate(jvm_gc_pause_seconds_sum{job="seckill-app"}[1m]))
sum by (instance) (rate(jvm_gc_pause_seconds_count{job="seckill-app"}[1m]))
max by (instance) (max_over_time(jvm_gc_pause_seconds_max{job="seckill-app"}[1m]))

# 分配 / 晋升速率
sum by (instance) (rate(jvm_gc_memory_allocated_bytes_total{job="seckill-app"}[1m]))
sum by (instance) (rate(jvm_gc_memory_promoted_bytes_total{job="seckill-app"}[1m]))

# 线程 / CPU / 请求量 / 延迟
jvm_threads_live_threads{job="seckill-app"}
process_cpu_usage{job="seckill-app"}
sum by (instance) (rate(http_server_requests_seconds_count{job="seckill-app"}[1m]))
histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="seckill-app"}[1m])))
histogram_quantile(0.99, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="seckill-app"}[1m])))

# Hikari / 启动耗时
hikaricp_connections_active{job="seckill-app"}
hikaricp_connections_idle{job="seckill-app"}
hikaricp_connections_pending{job="seckill-app"}
hikaricp_connections_max{job="seckill-app"}
application_ready_time_seconds{job="seckill-app"}
```
PROMQL

if [[ -n "$SCENARIO" ]]; then
  cat >>"$NOTE" <<EOF

## k6

- 场景：\`$SCENARIO\`，时长 \`$DURATION\`，run_id \`$RUN_ID\`，退出码 \`$K6_STATUS\`
- k6 写入 Prometheus 的查询：
  \`k6_http_reqs_total{run_id="$RUN_ID"}\`、\`k6_http_req_duration{run_id="$RUN_ID"}\`
- 趋势查询（Grafana）：\`k6_http_req_duration{run_id="$RUN_ID", stat="p(99)"}\`
EOF
fi

echo
echo "Result note written: $NOTE"
echo "Baseline flags: $BASELINE_FLAGS"
echo "Restore with : bash $SCRIPT_DIR/tune.sh --restore"
exit "$K6_STATUS"
