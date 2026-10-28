#!/usr/bin/env bash
# Static regression for Stage2 B0 review finding M1:
# every chaos.* runtime parameter introduced or touched by B0 (the app-proxy
# family of B0-05 and chaos.mysql-cluster.failure-container-check-enabled of
# B0-08 slice B) must be (a) declared as an env placeholder in
# application.yml, (b) FORWARDED into the chaos-controller container by
# compose.base.yml with the SAME default, and (c) listed in .env.example.
# Without (b) a .env override never reaches the container: compose passes an
# explicit env list, so an unwired key is decoration, not configuration
# (工程规约 §1 + stage2 plan §7.3; the B0-05 step doc's "raise the cap in .env"
# for B3 cursor pages depends on exactly this wiring).
#
# Run: bash code/lab/scripts/test-chaos-env-wiring.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
YML="$ROOT_DIR/code/services/chaos-controller/src/main/resources/application.yml"
COMPOSE="$ROOT_DIR/code/lab/docker/compose.base.yml"
ENV_EXAMPLE="$ROOT_DIR/code/lab/docker/.env.example"

failures=0
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }

for f in "$YML" "$COMPOSE" "$ENV_EXAMPLE"; do
  if [[ ! -f "$f" ]]; then
    printf 'ERROR: missing file %s\n' "$f" >&2
    exit 2
  fi
done

# The B0-scoped keys: app-proxy family (whole family wired because B0-05
# externalized it) plus the B0-08b failure-container-check switch.
KEYS=(
  CHAOS_APP_PROXY_INSTANCES
  CHAOS_APP_PROXY_SCHEME
  CHAOS_APP_PROXY_PORT
  CHAOS_APP_PROXY_CONNECT_TIMEOUT_SECONDS
  CHAOS_APP_PROXY_REQUEST_TIMEOUT_SECONDS
  CHAOS_APP_PROXY_MAX_REQUEST_BYTES
  CHAOS_APP_PROXY_MAX_RESPONSE_BYTES
  CHAOS_MYSQL_CLUSTER_FAILURE_CONTAINER_CHECK_ENABLED
)

# yml_default <KEY>: the default declared as ${KEY:default} in application.yml.
yml_default() {
  sed -n "s/.*\${$1:\([^}]*\)}.*/\1/p" "$YML" | head -n 1
}

# compose_default <KEY>: the default declared as ${KEY:-default} in compose.
compose_default() {
  sed -n "s/.*\${$1:-\([^}]*\)}.*/\1/p" "$COMPOSE" | head -n 1
}

for key in "${KEYS[@]}"; do
  if ! grep -q "\${$key:" "$YML"; then
    fail "$key: application.yml declares no env placeholder (B0 externalization contract)"
    continue
  fi
  if ! grep -q "$key" "$COMPOSE"; then
    fail "$key: compose.base.yml does not forward it to the chaos-controller container (.env overrides cannot take effect)"
    continue
  fi
  if ! grep -q "$key" "$ENV_EXAMPLE"; then
    fail "$key: no .env.example entry (stage2 plan §7.3)"
    continue
  fi
  ydef="$(yml_default "$key")"
  cdef="$(compose_default "$key")"
  if [[ "$ydef" != "$cdef" ]]; then
    fail "$key: default drift (application.yml '$ydef' vs compose '$cdef')"
    continue
  fi
  pass "$key: yml placeholder + compose forwarding + .env.example entry, defaults match ('$ydef')"
done

# Stage2 B1-01 ledger / B1-03 metrics / B1-04 pre-check keys are discovered
# instead of listed: every ${LAB_LEDGER_*:...} / ${LAB_METRICS_*:...} /
# ${LAB_PRECHECK_*:...} / ${LAB_SOURCE_COMMIT:...} placeholder in the controller
# yml must reach the container and be documented in .env.example. Defaults are
# not compared here because several intentionally nest another placeholder
# (e.g. ${LAB_LEDGER_MYSQL_MODE:${MYSQL_MODE:standalone}}), which the simple sed
# extraction above cannot normalise.
DISCOVERED_KEYS=$(grep -o '\${LAB_LEDGER_[A-Z0-9_]*:\|\${LAB_METRICS_[A-Z0-9_]*:\|\${LAB_PRECHECK_[A-Z0-9_]*:\|\${LAB_SOURCE_COMMIT:' "$YML" \
  | sed 's/^\${//; s/:$//' | sort -u)
if [[ -z "$DISCOVERED_KEYS" ]]; then
  fail "no LAB_LEDGER_* / LAB_METRICS_* / LAB_PRECHECK_* placeholder found in application.yml (ledger/metrics/precheck wiring missing)"
else
  while read -r key; do
    [[ -z "$key" ]] && continue
    if ! grep -q "$key" "$COMPOSE"; then
      fail "$key: compose.base.yml does not forward it to the chaos-controller container"
      continue
    fi
    if ! grep -q "$key" "$ENV_EXAMPLE"; then
      fail "$key: no .env.example entry"
      continue
    fi
    pass "$key: yml placeholder + compose forwarding + .env.example entry"
  done <<< "$DISCOVERED_KEYS"
fi

if (( failures > 0 )); then
  printf 'CHAOS ENV WIRING FAILED (%d)\n' "$failures" >&2
  exit 1
fi
printf 'CHAOS ENV WIRING PASSED (%d chaos keys + %d ledger/metrics/precheck keys)\n' \
  "${#KEYS[@]}" "$(grep -c . <<< "$DISCOVERED_KEYS")"
