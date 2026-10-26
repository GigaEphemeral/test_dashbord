#!/usr/bin/env bash
# Zero-dependency static safety tests for the Task 20a scripts.
# These assert the invariants of the review fixes without touching Docker:
#   1. no credential may appear on a command line (argv) in reset-lab.sh
#   2. an empty compose project label must be rejected, not allowed through
#   3. queue existence must be checked in preflight (before any destructive step)
#   4. the app tier must be auto-recovered when the reset fails mid-way
#   5. bench.sh must reject overrides of its internal variable names
#
# Run: bash code/lab/scripts/test-lab-safety.sh
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESET="$DIR/reset-lab.sh"
BENCH="$DIR/bench.sh"
failures=0

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }

expect_absent() { # name, file, pattern
  if grep -qE -- "$3" "$2"; then fail "$1"; else pass "$1"; fi
}
expect_present() { # name, file, pattern
  if grep -qE -- "$3" "$2"; then pass "$1"; else fail "$1"; fi
}

# --- 1. credentials never on argv -------------------------------------------
expect_absent 'reset-lab.sh: no curl -u anywhere (auth goes through stdin)' \
  "$RESET" '^[^#]*curl.*[[:space:]]-u([=[:space:]]|$)'
expect_absent 'reset-lab.sh: no -e MYSQL_PWD= argv leak' \
  "$RESET" '^[^#].*-e MYSQL_PWD='
expect_present 'reset-lab.sh: MySQL password goes to a chmod-600 env file' \
  "$RESET" 'chmod 600 "\$MYSQL_PWD_FILE"'
expect_present 'reset-lab.sh: env file consumed via docker exec --env-file' \
  "$RESET" '--env-file "\$\(host_path "\$MYSQL_PWD_FILE"\)"'
expect_present 'reset-lab.sh: rabbit auth fed via curl --config - (stdin)' \
  "$RESET" '--config -'
expect_absent 'reset-lab.sh: no argv -u flag carries a password expansion' \
  "$RESET" '^[^#]*-u[=" ]*\$\{?(MYSQL_PASSWORD|RABBITMQ_DEFAULT_PASS)'

# --- 2. project label boundary ----------------------------------------------
expect_absent 'reset-lab.sh: empty-label bypass removed' \
  "$RESET" '\[\[ -n "\$project"'
label_checks="$(grep -cE -- '\[\[ "\$project" != "\$COMPOSE_PROJECT" \]\]' "$RESET")"
if [[ "$label_checks" == "2" ]]; then
  pass 'reset-lab.sh: strict label equality enforced in both check loops'
else
  fail 'reset-lab.sh: strict label equality enforced in both check loops (found '"$label_checks"'/2)'
fi

# --- 3. queue existence preflight --------------------------------------------
expect_present 'reset-lab.sh: queue probe requires management API HTTP 200' \
  "$RESET" 'queue_probe_code.*!= "200"'
probe_line="$(grep -n 'queue_probe_code' "$RESET" | head -n 1 | cut -d: -f1)"
stop_line="$(grep -n 'compose stop' "$RESET" | head -n 1 | cut -d: -f1)"
if [[ -n "$probe_line" && -n "$stop_line" ]] && (( probe_line < stop_line )); then
  pass 'reset-lab.sh: queue probe runs before the app tier is stopped'
else
  fail 'reset-lab.sh: queue probe runs before the app tier is stopped'
fi
mysql_probe_line="$(grep -n "mysql_scalar 'SELECT 1;'" "$RESET" | head -n 1 | cut -d: -f1)"
if [[ -n "$mysql_probe_line" && -n "$stop_line" ]] && (( mysql_probe_line < stop_line )); then
  pass 'reset-lab.sh: MySQL read probe runs before the app tier is stopped'
else
  fail 'reset-lab.sh: MySQL read probe runs before the app tier is stopped'
fi

# --- 4. app-tier failure recovery --------------------------------------------
expect_present 'reset-lab.sh: EXIT trap installed for secret cleanup + recovery' \
  "$RESET" 'trap cleanup_and_recover EXIT'
if grep -qE -- 'local rc=\$' "$RESET"; then
  pass 'reset-lab.sh: trap captures the original exit code'
else
  fail 'reset-lab.sh: trap captures the original exit code'
fi
set_line="$(grep -n 'APP_RECOVERY_NEEDED=true' "$RESET" | head -n 1 | cut -d: -f1)"
# first `APP_RECOVERY_NEEDED=false` AFTER the stop (the trap body and the
# initial declaration assign false earlier; only a post-start clear counts)
clear_line="$(awk -v s="$set_line" 'NR > s && /APP_RECOVERY_NEEDED=false/ {print NR; exit}' "$RESET")"
if [[ -n "$set_line" && -n "$clear_line" ]] && (( set_line < clear_line )); then
  pass 'reset-lab.sh: recovery window covers stop..start'
else
  fail 'reset-lab.sh: recovery window covers stop..start'
fi

# --- 5. bench.sh reserved override names -------------------------------------
expect_present 'bench.sh: reserved-name guard exists' \
  "$BENCH" 'reserved_override_re='
reserved_re="$(sed -n "s/^ *reserved_override_re='\(.*\)'$/\1/p" "$BENCH")"
if [[ -z "$reserved_re" ]]; then
  fail 'bench.sh: reserved override regex extractable'
else
  pass 'bench.sh: reserved override regex extractable'
  for name in PATH HOME RUN_ID SCENARIO K6_IMAGE K6_NETWORK K6_DIR K6_REPORT_DIR \
    K6_SUMMARY_EXPORT K6_FORWARD_ENV BENCH_DOC_DIR RESET_BASELINE_STOCK; do
    if [[ "$name" =~ $reserved_re ]]; then
      pass "bench.sh: override $name rejected"
    else
      fail "bench.sh: override $name rejected"
    fi
  done
  for name in ERROR_RATE LOAD_DURATION K6_PROMETHEUS_RW_SERVER_URL SECKILL_PRODUCT_ID; do
    if [[ "$name" =~ $reserved_re ]]; then
      fail "bench.sh: scenario override $name still allowed"
    else
      pass "bench.sh: scenario override $name still allowed"
    fi
  done
fi

# --- 6. hygiene ---------------------------------------------------------------
expect_absent 'reset-lab.sh: no set -x (would print expanded credentials)' \
  "$RESET" '^[[:space:]]*set -x'
leaks="$(grep -nE -- '^[^#]*(echo|printf).*\$\{?(MYSQL_PASSWORD|RABBITMQ_DEFAULT_PASS)' "$RESET" \
  | grep -v 'MYSQL_PWD_FILE' | grep -v 'curl_config_quote' || true)"
if [[ -z "$leaks" ]]; then
  pass 'reset-lab.sh: passwords only reach the env file and the curl stdin pipe'
else
  fail 'reset-lab.sh: passwords only reach the env file and the curl stdin pipe'
  printf '%s\n' "$leaks" >&2
fi

echo
if (( failures > 0 )); then
  echo "LAB SAFETY TESTS FAILED: $failures failure(s)" >&2
  exit 1
fi
echo "LAB SAFETY TESTS PASSED."
