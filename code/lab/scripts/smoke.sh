#!/usr/bin/env bash
# Full-stack smoke test for the java-learn-lab HTTP surface.
#
# Flow: login -> product read -> idempotent order create/replay/read -> seckill
# order (async 202 ACCEPTED + poll, or 200/202 synchronous CREATED when the mq
# toggle is off or the seckillBackend circuit is open). Only the public Nginx
# entry is used; the script never touches MySQL/Redis/RabbitMQ directly.
#
# Every value below can be overridden from the environment or from the lab
# .env file (environment wins). Paths and timeouts are never hardcoded outside
# these defaults.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="${SMOKE_ENV_FILE:-$ROOT_DIR/code/lab/docker/.env}"

# Read one KEY=value from the lab .env without sourcing it (credentials stay
# out of this process). Missing file/key yields an empty string.
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

# --- Configuration (environment > .env > default) -------------------------
SMOKE_BASE_URL="${SMOKE_BASE_URL:-$(dotenv_value SMOKE_BASE_URL)}"
SMOKE_BASE_URL="${SMOKE_BASE_URL:-http://127.0.0.1:8080}"
SMOKE_BASE_URL="${SMOKE_BASE_URL%/}"

# The lab token is not required by the public business API; it is kept here so
# operators can extend the smoke flow with /lab-api checks without hardcoding
# the secret in this script.
SMOKE_LAB_TOKEN="${SMOKE_LAB_TOKEN:-${LAB_TOKEN:-$(dotenv_value SMOKE_LAB_TOKEN)}}"
SMOKE_LAB_TOKEN="${SMOKE_LAB_TOKEN:-$(dotenv_value LAB_TOKEN)}"
SMOKE_LAB_TOKEN="${SMOKE_LAB_TOKEN:-lab-token}"

SMOKE_PRODUCT_ID="${SMOKE_PRODUCT_ID:-$(dotenv_value SMOKE_PRODUCT_ID)}"
SMOKE_PRODUCT_ID="${SMOKE_PRODUCT_ID:-1}"

SMOKE_SECKILL_PRODUCT_ID="${SMOKE_SECKILL_PRODUCT_ID:-$(dotenv_value SMOKE_SECKILL_PRODUCT_ID)}"
SMOKE_SECKILL_PRODUCT_ID="${SMOKE_SECKILL_PRODUCT_ID:-20}"

SMOKE_USER="${SMOKE_USER:-$(dotenv_value SMOKE_USER)}"
SMOKE_USER="${SMOKE_USER:-smoke-user}"

SMOKE_TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-$(dotenv_value SMOKE_TIMEOUT_SECONDS)}"
SMOKE_TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-10}"

SMOKE_POLL_RETRIES="${SMOKE_POLL_RETRIES:-$(dotenv_value SMOKE_POLL_RETRIES)}"
SMOKE_POLL_RETRIES="${SMOKE_POLL_RETRIES:-30}"

SMOKE_POLL_INTERVAL_SECONDS="${SMOKE_POLL_INTERVAL_SECONDS:-$(dotenv_value SMOKE_POLL_INTERVAL_SECONDS)}"
SMOKE_POLL_INTERVAL_SECONDS="${SMOKE_POLL_INTERVAL_SECONDS:-1}"

PASSED=0
FAILED=0
LAST_CODE=""
LAST_BODY=""
LAST_RESPONSE=""
SMOKE_TOKEN=""

pass() {
  printf 'PASS  %s\n' "$1"
  PASSED=$((PASSED + 1))
}

fail() {
  printf 'FAIL  %s\n' "$1"
  if [[ -n "${2:-}" ]]; then
    printf '      %s\n' "$2"
  fi
  if [[ -n "$LAST_RESPONSE" ]]; then
    printf '      last response (HTTP %s): %s\n' "${LAST_CODE:-unknown}" "${LAST_BODY:0:500}"
  fi
  FAILED=$((FAILED + 1))
}

require_positive_int() {
  local label="$1" value="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    printf 'ERROR: %s must be a positive integer, got "%s".\n' "$label" "$value" >&2
    exit 2
  fi
}

json_string() {
  local field="$1" body="$2"
  sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" <<<"$body" | head -n 1
}

json_number() {
  local field="$1" body="$2"
  sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" <<<"$body" | head -n 1
}

# GET/POST helper. The trailing status line keeps the body readable while the
# status is parsed exactly; transport errors become HTTP 000 with the curl
# message in LAST_BODY.
http_request() {
  local method="$1" path="$2" data="${3:-}" extra_header="${4:-}"
  local args=(--silent --show-error --max-time "$SMOKE_TIMEOUT_SECONDS"
    -X "$method" -H 'Accept: application/json' -w $'\n%{http_code}')
  if [[ -n "$extra_header" ]]; then
    args+=(-H "$extra_header")
  fi
  if [[ -n "$data" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$data")
  fi
  LAST_RESPONSE="$(curl "${args[@]}" "${SMOKE_BASE_URL}${path}" 2>&1 || true)"
  if [[ "$LAST_RESPONSE" == *$'\n'* ]]; then
    LAST_CODE="${LAST_RESPONSE##*$'\n'}"
    LAST_BODY="${LAST_RESPONSE%$'\n'*}"
  else
    LAST_CODE="000"
    LAST_BODY="$LAST_RESPONSE"
  fi
}

require_positive_int SMOKE_TIMEOUT_SECONDS "$SMOKE_TIMEOUT_SECONDS"
require_positive_int SMOKE_POLL_RETRIES "$SMOKE_POLL_RETRIES"
require_positive_int SMOKE_PRODUCT_ID "$SMOKE_PRODUCT_ID"
require_positive_int SMOKE_SECKILL_PRODUCT_ID "$SMOKE_SECKILL_PRODUCT_ID"
if [[ ! "$SMOKE_POLL_INTERVAL_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "ERROR: SMOKE_POLL_INTERVAL_SECONDS must be a non-negative integer." >&2
  exit 2
fi

run_id="$(date +%s)-${RANDOM}-$$"
printf 'Smoke target: %s (product=%s, seckill product=%s, run=%s)\n' \
  "$SMOKE_BASE_URL" "$SMOKE_PRODUCT_ID" "$SMOKE_SECKILL_PRODUCT_ID" "$run_id"

# --- 1. Mock login ---------------------------------------------------------
login_payload="{\"userId\":\"${SMOKE_USER}-${run_id}\"}"
http_request POST /api/login "$login_payload"
SMOKE_TOKEN="$(json_string token "$LAST_BODY")"
login_expires="$(json_number expiresInSeconds "$LAST_BODY")"
if [[ "$LAST_CODE" == "200" ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$LAST_BODY" \
  && [[ -n "$SMOKE_TOKEN" ]] \
  && [[ "$login_expires" =~ ^[0-9]+$ ]] && (( login_expires > 0 )); then
  pass "POST /api/login returns a token (expiresInSeconds=${login_expires})"
else
  fail "POST /api/login returns a token" "HTTP ${LAST_CODE:-000}"
fi

auth_header="Authorization: Bearer ${SMOKE_TOKEN:-none}"

# --- 2. Product read -------------------------------------------------------
http_request GET "/api/products/${SMOKE_PRODUCT_ID}" "" "$auth_header"
product_id="$(json_number id "$LAST_BODY")"
if [[ "$LAST_CODE" == "200" ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$LAST_BODY" \
  && [[ "$product_id" == "$SMOKE_PRODUCT_ID" ]]; then
  pass "GET /api/products/${SMOKE_PRODUCT_ID} returns 200 code=0 id=${product_id}"
else
  fail "GET /api/products/${SMOKE_PRODUCT_ID} returns 200 code=0" \
    "HTTP ${LAST_CODE:-000} id='${product_id}'"
fi

# --- 3. Idempotent synchronous order --------------------------------------
order_key="smoke-order-${run_id}"
order_payload="{\"userId\":\"${SMOKE_USER}\",\"productId\":${SMOKE_PRODUCT_ID},\"quantity\":1}"
http_request POST /api/orders "$order_payload" "Idempotency-Key: ${order_key}"
order_id="$(json_number id "$LAST_BODY")"
order_created=false
if [[ "$LAST_CODE" == "200" ]] \
  && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$LAST_BODY" \
  && [[ "$order_id" =~ ^[0-9]+$ ]]; then
  order_created=true
  pass "POST /api/orders creates order id=${order_id} (key=${order_key})"
else
  fail "POST /api/orders creates an order" "HTTP ${LAST_CODE:-000}"
fi

if $order_created; then
  http_request POST /api/orders "$order_payload" "Idempotency-Key: ${order_key}"
  replay_id="$(json_number id "$LAST_BODY")"
  if [[ "$LAST_CODE" == "200" ]] \
    && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$LAST_BODY" \
    && [[ "$replay_id" == "$order_id" ]]; then
    pass "repeat Idempotency-Key returns the same order id=${replay_id}"
  else
    fail "repeat Idempotency-Key returns the same order id" \
      "HTTP ${LAST_CODE:-000} id='${replay_id}' expected='${order_id}'"
  fi

  http_request GET "/api/orders/${order_id}" "" "$auth_header"
  fetched_id="$(json_number id "$LAST_BODY")"
  if [[ "$LAST_CODE" == "200" ]] \
    && grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$LAST_BODY" \
    && [[ "$fetched_id" == "$order_id" ]]; then
    pass "GET /api/orders/${order_id} returns the created order"
  else
    fail "GET /api/orders/${order_id} returns the created order" \
      "HTTP ${LAST_CODE:-000} id='${fetched_id}'"
  fi
else
  fail "repeat Idempotency-Key returns the same order id" "order creation failed; skipped"
  fail "GET /api/orders returns the created order" "order creation failed; skipped"
fi

# --- 4. Seckill order ------------------------------------------------------
# Two success shapes are accepted, mirroring the server contract:
#   a) async admission: HTTP 202 + status=ACCEPTED + requestId -> poll until CREATED;
#   b) synchronous terminal order: HTTP 200 (mq toggle off) or HTTP 202 with the
#      seckillBackend circuit OPEN (Task 16/48d45fb degradation keeps 202 but the
#      body is status=CREATED + orderId, requestId=null) -> no polling possible.
# 409/429 are deliberate business outcomes and fail the smoke with a clear reason.
seckill_key="smoke-seckill-${run_id}"
seckill_payload="{\"userId\":\"${SMOKE_USER}\",\"productId\":${SMOKE_SECKILL_PRODUCT_ID},\"quantity\":1}"
http_request POST /api/seckill/orders "$seckill_payload" "Idempotency-Key: ${seckill_key}"
request_id="$(json_string requestId "$LAST_BODY")"
seckill_status="$(json_string status "$LAST_BODY")"
seckill_order_id="$(json_number orderId "$LAST_BODY")"
seckill_code0=false
if grep -Eq '"code"[[:space:]]*:[[:space:]]*"0"' <<<"$LAST_BODY"; then
  seckill_code0=true
fi
accepted=false
terminated=false
if $seckill_code0 && [[ "$LAST_CODE" == "202" ]] \
  && [[ "$seckill_status" == "ACCEPTED" ]] && [[ -n "$request_id" ]]; then
  accepted=true
  pass "POST /api/seckill/orders accepted asynchronously (requestId=${request_id})"
elif $seckill_code0 && [[ "$LAST_CODE" =~ ^(200|202)$ ]] \
  && [[ "$seckill_status" == "CREATED" ]] && [[ "$seckill_order_id" =~ ^[0-9]+$ ]]; then
  terminated=true
  if [[ "$LAST_CODE" == "202" ]]; then
    pass "POST /api/seckill/orders served synchronously under degradation (orderId=${seckill_order_id})"
  else
    pass "POST /api/seckill/orders served synchronously, mq toggle off (orderId=${seckill_order_id})"
  fi
elif [[ "$LAST_CODE" == "409" ]]; then
  fail "POST /api/seckill/orders placed an order" \
    "HTTP 409 business conflict: sold out or seckill lock timeout"
elif [[ "$LAST_CODE" == "429" ]]; then
  fail "POST /api/seckill/orders placed an order" \
    "HTTP 429 rate limited by the seckill limiter"
else
  fail "POST /api/seckill/orders placed an order" \
    "HTTP ${LAST_CODE:-000} status='${seckill_status}' (expected 202 ACCEPTED+requestId, or 200/202 CREATED+orderId when degraded)"
fi

poll_status=""
poll_order_id=""
if $accepted; then
  for attempt in $(seq 1 "$SMOKE_POLL_RETRIES"); do
    http_request GET "/api/seckill/orders/${request_id}" "" "$auth_header"
    poll_status="$(json_string status "$LAST_BODY")"
    poll_order_id="$(json_number orderId "$LAST_BODY")"
    if [[ "$LAST_CODE" == "200" ]] \
      && [[ "$poll_status" == "CREATED" ]] \
      && [[ "$poll_order_id" =~ ^[0-9]+$ ]]; then
      break
    fi
    if (( attempt < SMOKE_POLL_RETRIES )); then
      sleep "$SMOKE_POLL_INTERVAL_SECONDS"
    fi
  done
  if [[ "$LAST_CODE" == "200" ]] \
    && [[ "$poll_status" == "CREATED" ]] \
    && [[ "$poll_order_id" =~ ^[0-9]+$ ]]; then
    pass "GET /api/seckill/orders/${request_id} reaches CREATED (orderId=${poll_order_id})"
  else
    fail "GET /api/seckill/orders/${request_id} reaches CREATED" \
      "HTTP ${LAST_CODE:-000} status='${poll_status}' orderId='${poll_order_id}' after ${SMOKE_POLL_RETRIES} polls"
  fi
elif $terminated; then
  pass "seckill order already CREATED without polling (degradation path; no requestId to poll)"
else
  fail "GET /api/seckill/orders reaches CREATED" "accept failed; poll skipped"
fi

# --- Summary ---------------------------------------------------------------
printf '\nSmoke checks: %d passed, %d failed.\n' "$PASSED" "$FAILED"
if (( FAILED > 0 )); then
  echo "SMOKE FAILED." >&2
  exit 1
fi
echo "SMOKE PASSED."
