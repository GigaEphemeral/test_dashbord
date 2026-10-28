#!/usr/bin/env bash
# Browser smoke test for the lab console (code/lab/dashboard).
#
# B1-05 rewrote the console as a seven-section sidebar workspace and rewrote
# this smoke against it: 总览 / 场景向导 / 实例与 JVM / Benchmark / 实时监控 /
# 故障与缓存实验 / 实验记录. The ledger block creates a uniquely named DRAFT
# run, reads its pre-check, drives start/stop through the operation contract,
# checks metric quality badges and proves a reload restores the selected run.
# A BLOCKED pre-check is asserted through its evidence instead of being forced.
# The animation block injects one 10s `freeze` on jl-app-2 to assert the fault
# badge, then calls chaos stop-all; start and exit both call stop-all, so the
# script leaves no active fault and the app instances healthy. It does reset the
# wizard and stop background traffic at the end.
#
# Every value can be overridden from the environment or the lab .env file
# (environment wins). The token is never printed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="${SMOKE_ENV_FILE:-$ROOT_DIR/code/lab/docker/.env}"
DASHBOARD_DIR="$ROOT_DIR/code/lab/dashboard"
SMOKE_JS="${CONSOLE_SMOKE_SCRIPT:-$SCRIPT_DIR/console-smoke.mjs}"

# Read one KEY=value from the lab .env without sourcing it. Missing file/key
# yields an empty string.
dotenv_value() {
  local key="$1" line value
  [[ -r "$ENV_FILE" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" =~ ^[[:space:]]*${key}[[:space:]]*=(.*)$ ]]; then
      value="${BASH_REMATCH[1]}"
      value="${value%\"}"
      value="${value#\"}"
      value="${value%\'}"
      value="${value#\'}"
      printf '%s' "$value"
      return 0
    fi
  done < "$ENV_FILE"
  return 0
}

usage() {
  cat <<'EOF'
Usage: console-smoke.sh [--headed] [--no-build]

Environment (environment > lab .env > default):
  CONSOLE_URL                 console entry, default http://127.0.0.1:8080/lab/
  LAB_API_BASE                controller base, default http://127.0.0.1:8080/lab-api
  LAB_TOKEN                   X-Lab-Token value (never printed)
  SMOKE_SCENARIO_ID           wizard scenario to load, default quick-failover
  SMOKE_LOG_CONTAINER         container whose logs are read, default jl-app-1
  SMOKE_ACTION_TIMEOUT_MS     per-assertion polling budget, default 20000
  SMOKE_RUN_SCENARIO          ledger run scenario, default console-smoke-<epoch>
  SMOKE_RUN_SCENARIO_PREFIX   ownership prefix for leftover-run cleanup, default console-smoke-
  SMOKE_RUN_PARAMS            ledger run params JSON, default {"note":"console-smoke",...}
  SMOKE_KPI_COUNT             observe KPI card count, default 12
  SMOKE_RUN_METRIC_COUNT      ledger metric card count, default 13
  SMOKE_HEADED                set to 1 for a visible browser
  PLAYWRIGHT_MODULE           directory of an installed playwright package
  CONSOLE_SMOKE_SCRIPT        path to the smoke script, default alongside this file
EOF
}

HEADED="${SMOKE_HEADED:-}"
BUILD=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --headed) HEADED=1; shift ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

CONSOLE_URL="${CONSOLE_URL:-$(dotenv_value CONSOLE_URL)}"
CONSOLE_URL="${CONSOLE_URL:-http://127.0.0.1:8080/lab/}"

LAB_API_BASE="${LAB_API_BASE:-$(dotenv_value LAB_API_BASE)}"
LAB_API_BASE="${LAB_API_BASE:-http://127.0.0.1:8080/lab-api}"
LAB_API_BASE="${LAB_API_BASE%/}"

LAB_TOKEN="${LAB_TOKEN:-$(dotenv_value LAB_TOKEN)}"
LAB_TOKEN="${LAB_TOKEN:-lab-token}"

if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node is required to run the console smoke but was not found in PATH." >&2
  exit 1
fi

# --- Preflight: the stack must answer before a browser is started -----------
if ! curl -fsS --max-time 5 "${LAB_API_BASE}/health" >/dev/null 2>&1; then
  echo "ERROR: the lab console is not reachable at ${LAB_API_BASE}/health." >&2
  echo "Start the stack first: bash $SCRIPT_DIR/up.sh" >&2
  exit 1
fi

# --- Dashboard build --------------------------------------------------------
# dist is gitignored and bind-mounted read-only into Nginx, so a stale build
# silently serves the previous UI. Rebuild when any tracked input is newer.
if [[ "$BUILD" == "1" ]]; then
  need_build=0
  if [[ ! -f "$DASHBOARD_DIR/dist/index.html" ]]; then
    need_build=1
  elif [[ -n "$(find \
        "$DASHBOARD_DIR/src" \
        "$DASHBOARD_DIR/index.html" \
        "$DASHBOARD_DIR/vite.config.js" \
        -newer "$DASHBOARD_DIR/dist/index.html" -print -quit 2>/dev/null)" ]]; then
    need_build=1
  fi
  if [[ "$need_build" == "1" ]]; then
    if ! command -v npm >/dev/null 2>&1; then
      echo "ERROR: npm is required to rebuild the console but was not found in PATH." >&2
      echo "Build it manually (npm run build in $DASHBOARD_DIR) or pass --no-build." >&2
      exit 1
    fi
    echo "Console sources are newer than dist; running npm run build..."
    if ! (cd "$DASHBOARD_DIR" && npm run build); then
      echo "ERROR: console build failed." >&2
      exit 1
    fi
  fi
fi

if [[ ! -f "$DASHBOARD_DIR/dist/index.html" ]]; then
  echo "ERROR: $DASHBOARD_DIR/dist/index.html is missing; Nginx would serve an empty console." >&2
  echo "Run: (cd code/lab/dashboard && npm run build)" >&2
  exit 1
fi

# --- Playwright resolution (offline-friendly) --------------------------------
if [[ -z "${PLAYWRIGHT_MODULE:-}" ]]; then
  for candidate in \
    "$DASHBOARD_DIR/node_modules/playwright" \
    "$DASHBOARD_DIR/node_modules/playwright-core" \
    "$ROOT_DIR/node_modules/playwright"; do
    if [[ -d "$candidate" ]]; then
      PLAYWRIGHT_MODULE="$candidate"
      break
    fi
  done
fi
if [[ -z "${PLAYWRIGHT_MODULE:-}" ]]; then
  # npx keeps a cache copy per package hash; use the newest installed playwright.
  while IFS= read -r candidate; do
    [[ -d "$candidate" ]] || continue
    PLAYWRIGHT_MODULE="$candidate"
  done < <(ls -d "$HOME"/AppData/Local/npm-cache/_npx/*/node_modules/playwright 2>/dev/null || true)
fi
if [[ -z "${PLAYWRIGHT_MODULE:-}" ]]; then
  echo "ERROR: Playwright is not installed anywhere this script can find." >&2
  echo "Install it (npm i -D playwright && npx playwright install chromium) or set" >&2
  echo "PLAYWRIGHT_MODULE to the directory containing its package.json." >&2
  exit 1
fi

SMOKE_SCENARIO_ID="${SMOKE_SCENARIO_ID:-$(dotenv_value SMOKE_SCENARIO_ID)}"
SMOKE_SCENARIO_ID="${SMOKE_SCENARIO_ID:-quick-failover}"
SMOKE_LOG_CONTAINER="${SMOKE_LOG_CONTAINER:-$(dotenv_value SMOKE_LOG_CONTAINER)}"
SMOKE_LOG_CONTAINER="${SMOKE_LOG_CONTAINER:-jl-app-1}"
SMOKE_ACTION_TIMEOUT_MS="${SMOKE_ACTION_TIMEOUT_MS:-$(dotenv_value SMOKE_ACTION_TIMEOUT_MS)}"
SMOKE_ACTION_TIMEOUT_MS="${SMOKE_ACTION_TIMEOUT_MS:-20000}"
# Ledger/Ui overrides keep their defaults inside the Node script; an empty
# value here means "use the script default".
SMOKE_RUN_SCENARIO="${SMOKE_RUN_SCENARIO:-$(dotenv_value SMOKE_RUN_SCENARIO)}"
SMOKE_RUN_SCENARIO_PREFIX="${SMOKE_RUN_SCENARIO_PREFIX:-$(dotenv_value SMOKE_RUN_SCENARIO_PREFIX)}"
SMOKE_RUN_PARAMS="${SMOKE_RUN_PARAMS:-$(dotenv_value SMOKE_RUN_PARAMS)}"
SMOKE_KPI_COUNT="${SMOKE_KPI_COUNT:-$(dotenv_value SMOKE_KPI_COUNT)}"
SMOKE_RUN_METRIC_COUNT="${SMOKE_RUN_METRIC_COUNT:-$(dotenv_value SMOKE_RUN_METRIC_COUNT)}"

echo "Console smoke: $CONSOLE_URL"
echo "  api base   : $LAB_API_BASE"
echo "  playwright : $PLAYWRIGHT_MODULE"
echo "  scenario   : $SMOKE_SCENARIO_ID"
echo "  logs from  : $SMOKE_LOG_CONTAINER"
echo "  token      : (from environment or lab .env, not printed)"

export CONSOLE_URL LAB_API_BASE LAB_TOKEN PLAYWRIGHT_MODULE SMOKE_HEADED="$HEADED"
export SMOKE_SCENARIO_ID SMOKE_LOG_CONTAINER SMOKE_ACTION_TIMEOUT_MS
export SMOKE_RUN_SCENARIO SMOKE_RUN_SCENARIO_PREFIX SMOKE_RUN_PARAMS
export SMOKE_KPI_COUNT SMOKE_RUN_METRIC_COUNT

exec node "$SMOKE_JS" "$@"
