#!/usr/bin/env bash
# Regression harness for smoke.sh (Task 18 review).
#
# Runs the real smoke.sh against the zero-dependency node mock server in five
# modes and asserts the exit code plus the decisive PASS/FAIL line:
#   async     202 ACCEPTED + requestId        -> smoke must poll and PASS
#   degraded  202 CREATED + orderId (circuit  -> smoke must PASS without
#             open per 48d45fb)                  polling (the Task 18 gap)
#   syncoff   200 CREATED + orderId (mq off)  -> smoke must PASS
#   conflict  409 sold out                    -> smoke must FAIL with reason
#   limited   429 rate limited                -> smoke must FAIL with reason
#
# Never touches the shared lab stack; the mock only listens on 127.0.0.1.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE="${SCRIPT_DIR}/smoke.sh"
MOCK="${SCRIPT_DIR}/smoke-mock-server.mjs"

PASSED=0
FAILED=0

run_case() {
  local mode="$1" expected_exit="$2" expected_line="$3"
  local log_dir port smoke_out code
  log_dir="$(mktemp -d)"
  node "$MOCK" 0 "$mode" >"$log_dir/mock.log" 2>&1 &
  local mock_pid=$!
  trap 'kill '"$mock_pid"' 2>/dev/null || true' RETURN

  port=""
  for _ in $(seq 1 50); do
    port="$(sed -n 's/.*port=\([0-9]*\).*/\1/p' "$log_dir/mock.log" | head -n1)"
    [[ -n "$port" ]] && break
    sleep 0.1
  done
  if [[ -z "$port" ]]; then
    echo "FAIL [${mode}] mock server did not start: $(cat "$log_dir/mock.log")"
    FAILED=$((FAILED + 1))
    return
  fi

  smoke_out="$(SMOKE_ENV_FILE=/dev/null \
    SMOKE_BASE_URL="http://127.0.0.1:${port}" \
    SMOKE_TIMEOUT_SECONDS=5 \
    SMOKE_POLL_RETRIES=5 \
    SMOKE_POLL_INTERVAL_SECONDS=0 \
    bash "$SMOKE" 2>&1)" && code=0 || code=$?

  if [[ "$code" == "$expected_exit" ]] && grep -Fq "$expected_line" <<<"$smoke_out"; then
    echo "PASS [${mode}] exit=${code} matches \"${expected_line}\""
    PASSED=$((PASSED + 1))
  else
    echo "FAIL [${mode}] exit=${code} (expected ${expected_exit}); smoke output:"
    sed 's/^/    /' <<<"$smoke_out"
    FAILED=$((FAILED + 1))
  fi
  rm -rf "$log_dir"
}

run_case async     0 'GET /api/seckill/orders/mock-request-1 reaches CREATED (orderId=4242)'
run_case degraded  0 'served synchronously under degradation'
run_case syncoff   0 'served synchronously, mq toggle off'
run_case conflict  1 'HTTP 409 business conflict: sold out or seckill lock timeout'
run_case limited   1 'HTTP 429 rate limited by the seckill limiter'

echo "smoke regression: ${PASSED} passed, ${FAILED} failed"
(( FAILED == 0 ))
