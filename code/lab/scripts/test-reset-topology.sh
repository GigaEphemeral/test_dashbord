#!/usr/bin/env bash
# Mock-docker/curl tests for reset-lab.sh topology resolution (Stage2 B0-06).
#
# What it asserts (A25):
#   1. standalone topology  -> the reset targets jl-mysql (socket) and jl-redis
#   2. router topology      -> the reset targets the CLUSTER (exec in a
#      jl-mysql-<n> node, connecting through mysql-router:6446), never jl-mysql
#   3. router declared, Router down -> per-node fallback onto the PRIMARY node
#   4. unresolvable topology -> exit 2 with the observed-container report
#   5. declared standalone while BOTH stacks run -> standalone (the app's own
#      switch wins; the cluster is only a fallback when standalone is down)
#   6. REDIS_MODE=sentinel -> FLUSHDB every running jl-redis-<n>, verify the master
#   7. --dry-run changes nothing (no compose stop/start/exec writes in the log)
#   8. a full mocked --yes run passes the baseline via the cluster route
#
# Docker/curl are shimmed by generated stubs in a temp bin dir; the real stack
# is never touched and no lifecycle command reaches a real daemon.
#
# Run: bash code/lab/scripts/test-reset-topology.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESET="$SCRIPT_DIR/reset-lab.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/jl-reset-topology-XXXXXX")"
MOCK_BIN="$TEST_ROOT/bin"
mkdir -p "$MOCK_BIN"
trap 'rm -rf "$TEST_ROOT"' EXIT

failures=0
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }

# --- mock docker ---------------------------------------------------------------
cat >"$MOCK_BIN/docker" <<'MOCKEOF'
#!/usr/bin/env bash
set -u
printf 'docker %s\n' "${*//$'\n'/ }" >> "$MOCK_DOCKER_LOG"
is_running() { case " ${MOCK_RUNNING:-} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

cmd=""; idx=0; argv=("$@")
while (( idx < ${#argv[@]} )); do
  case "${argv[idx]}" in
    compose|inspect|exec) cmd="${argv[idx]}"; rest=("${argv[@]:idx+1}"); break ;;
  esac
  ((idx++))
done
cmd="${cmd:-}"
rest=("${rest[@]:-}")

if [[ "$cmd" == "compose" ]]; then
  case " ${rest[*]} " in
    *" config "*) printf 'mysql\nredis\nrabbitmq\napp-1\napp-2\napp-3\n'; exit 0 ;;
    *" ps "*)
      after_ps=0
      for a in "${rest[@]}"; do
        if (( after_ps == 1 )); then
          [[ "$a" == -* ]] && continue
          printf 'cid-%s\n' "$a"; exit 0
        fi
        [[ "$a" == "ps" ]] && after_ps=1
      done; exit 0 ;;
  esac
  exit 0
fi

if [[ "$cmd" == "inspect" ]]; then
  fmt=""; name=""; prev=""
  for a in "${rest[@]}"; do
    [[ "$prev" == "-f" ]] && fmt="$a"
    [[ -n "$fmt" && "$a" != "-f" && "$a" != "$fmt" ]] && { name="$a"; break; }
    prev="$a"
  done
  [[ -n "$name" ]] || exit 1
  case "$fmt" in
    *Config.Labels*) is_running "$name" && { printf 'java-learn-lab\n'; exit 0; }; exit 1 ;;
    *State.Health*)  printf 'healthy\n'; exit 0 ;;
    *.State.Status*) is_running "$name" && { printf 'running\n'; exit 0; }; exit 1 ;;
    *.Name*)
      if [[ "$name" == cid-* ]]; then printf '/jl-%s\n' "${name#cid-}"; else printf '/%s\n' "$name"; fi
      exit 0 ;;
  esac
  exit 1
fi

if [[ "$cmd" == "exec" ]]; then
  container=""; prog=""; mode=""; args=()
  skip=0
  for a in "${rest[@]}"; do
    (( skip == 1 )) && { skip=0; continue; }
    [[ "$a" == "--env-file" ]] && { skip=1; continue; }
    if [[ -z "$container" ]]; then container="$a"; continue; fi
    if [[ -z "$prog" ]]; then prog="$a"; continue; fi
    args+=("$a")
  done
  is_running "$container" || { printf 'no such container: %s\n' "$container" >&2; exit 1; }
  if [[ "$prog" == "mysql" ]]; then
    sql="${args[-1]}"
    case "$sql" in
      *"SELECT 1;"*)           printf '1\n' ;;
      *"@@read_only"*)
        case " ${MOCK_PRIMARY:-} " in *" $container "*) printf '0\n' ;; *) printf '1\n' ;; esac ;;
      *COUNT*catalog_product*)  printf '3\n' ;;
      *"available <>"*)         printf '0\n' ;;
      *COUNT*inventory_stock*)  printf '3\n' ;;
      *"SELECT id, available"*) printf '1\t37\n2\t74\n3\t111\n' ;;
      *"MIN(available)"*)       printf '37\n' ;;
      *"MAX(available)"*)       printf '111\n' ;;
      *"SUM(reserved)"*)        printf '0\n' ;;
      *COUNT*order_order*)      printf '0\n' ;;
      *GROUP_CONCAT*)           printf '1,2,3\n' ;;
      *"TRUNCATE TABLE"*)       : ;;
      *)                        printf '\n' ;;
    esac
    exit 0
  fi
  if [[ "$prog" == "redis-cli" ]]; then
    sub=""; keys=()
    for a in "${args[@]}"; do
      case "$a" in
        ping|dbsize|flushdb|info|mget|get) sub="$a" ;;
      esac
      if [[ "$a" == "seckill:stock:"* ]]; then
        keys+=("${a##*seckill:stock:}")
      fi
    done
    case "$sub" in
      ping)   printf 'PONG\n' ;;
      dbsize) printf '30\n' ;;
      flushdb) printf 'OK\n' ;;
      info)
        case " ${MOCK_REDIS_MASTER:-} " in
          *" $container "*) printf '# Replication\r\nrole:master\r\nconnected_slaves:2\r\n' ;;
          *)                printf '# Replication\r\nrole:slave\r\n' ;;
        esac ;;
      mget)
        # Same deterministic baseline as Flyway V4 / reset-lab.sh.
        for product_id in "${keys[@]}"; do
          printf '%s\n' "$(( (product_id * 37) % 501 ))"
        done ;;
      *) printf '\n' ;;
    esac
    exit 0
  fi
  exit 0
fi
exit 0
MOCKEOF

# --- mock curl ------------------------------------------------------------------
cat >"$MOCK_BIN/curl" <<'MOCKEOF'
#!/usr/bin/env bash
set -u
printf 'curl %s\n' "$*" >> "$MOCK_CURL_LOG"
url=""; has_w=false
for a in "$@"; do
  case "$a" in
    http://*|https://*) url="$a" ;;
    -w) has_w=true ;;
  esac
done
# consume stdin (--config -) so the feeding printf never sees EPIPE
cat >/dev/null 2>&1 || true
if [[ "$url" == *"/api/queues/"* ]]; then
  case "$*" in
    *DELETE*) printf '204'; exit 0 ;;
  esac
  if $has_w; then printf '200'; else printf '{"name":"seckill.order.queue","messages_ready":0,"messages_unacknowledged":0}'; fi
  exit 0
fi
if [[ "$url" == *"/api/overview"* ]]; then
  if $has_w; then printf '200'; else printf '{"management_version":"mock"}'; fi
  exit 0
fi
if [[ "$url" == *"/health"* ]]; then printf 'ok'; exit 0; fi
exit 0
MOCKEOF

# --- env file fixture -------------------------------------------------------------
make_env() { # <MYSQL_MODE> [REDIS_MODE (omit for "not in .env">]
  local envf="$TEST_ROOT/env-$1${2:+-$2}.env"
  {
    echo "MYSQL_USER=seckill"
    echo "MYSQL_PASSWORD=mock-pw"
    echo "MYSQL_DATABASE=seckill"
    echo "RABBITMQ_DEFAULT_USER=guest"
    echo "RABBITMQ_DEFAULT_PASS=mock-pw"
    echo "MYSQL_MODE=$1"
    [[ -n "${2:-}" ]] && echo "REDIS_MODE=$2"
  } >"$envf"
  printf '%s' "$envf"
}

DEV_CONTAINERS="jl-rabbitmq jl-app-1 jl-app-2 jl-app-3"

run_reset() { # -> sets OUT/RC; args: env-file [extra argv...]
  local envf="$1"; shift
  OUT="$(RESET_ENV_FILE="$envf" \
    MOCK_DOCKER_LOG="$TEST_ROOT/docker.log" MOCK_CURL_LOG="$TEST_ROOT/curl.log" \
    RESET_TOPOLOGY_PROBE_RETRIES=1 RESET_TOPOLOGY_PROBE_INTERVAL_SECONDS=1 \
    PATH="$MOCK_BIN:$PATH" \
    bash "$RESET" "$@" 2>&1)"
  RC=$?
}

check() { # <name> <pattern>
  if grep -qF -- "$2" <<<"$OUT"; then pass "$1"; else
    fail "$1 (missing: $2)"; printf '%s\n' "$OUT" | sed -n '1,40p' >&2
  fi
}

# --- 1. standalone -----------------------------------------------------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis"
run_reset "$(make_env standalone)" --dry-run
if (( RC == 0 )); then pass 'standalone dry-run exits 0'; else fail 'standalone dry-run exits 0'; fi
check 'standalone: MySQL topology resolved to standalone' 'MySQL topology   : standalone'
check 'standalone: exec container is jl-mysql' 'MySQL target     : container=jl-mysql'
check 'standalone: Redis target is jl-redis' 'Redis targets    : jl-redis'

# --- 2. router / db-ha ---------------------------------------------------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis jl-mysql-router jl-mysql-1 jl-mysql-2 jl-mysql-3"
: >"$TEST_ROOT/docker.log"
run_reset "$(make_env router)" --dry-run
if (( RC == 0 )); then pass 'router dry-run exits 0'; else fail 'router dry-run exits 0'; fi
check 'router: MySQL topology resolved to cluster' 'MySQL topology   : cluster'
check 'router: exec runs in a cluster node' 'MySQL target     : container=jl-mysql-1'
check 'router: connection goes through mysql-router:6446' 'mysql-router:6446'
if grep -qF -- 'mysql -h mysql-router -P 6446' "$TEST_ROOT/docker.log"; then
  pass 'router: mocked mysql client argv carries -h mysql-router -P 6446'
else
  fail 'router: mocked mysql client argv carries -h mysql-router -P 6446'
fi
if grep -qE -- 'exec --env-file [^ ]+ jl-mysql ' "$TEST_ROOT/docker.log"; then
  fail 'router: standalone jl-mysql was NOT execed'
else
  pass 'router: standalone jl-mysql was NOT execed'
fi

# --- 3. router declared, Router container down: per-node PRIMARY fallback -------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis jl-mysql-1 jl-mysql-2 jl-mysql-3"
export MOCK_PRIMARY="jl-mysql-2"
run_reset "$(make_env router)" --dry-run
if (( RC == 0 )); then pass 'nodes-fallback dry-run exits 0'; else fail 'nodes-fallback dry-run exits 0'; fi
check 'nodes: exec targets the PRIMARY node' 'container=jl-mysql-2'
check 'nodes: route description says per-node direct' 'per-node direct: PRIMARY jl-mysql-2'

# --- 4. unresolvable topology ----------------------------------------------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-redis"
run_reset "$(make_env standalone)" --dry-run
if (( RC == 2 )); then pass 'unknown topology exits 2'; else fail 'unknown topology exits 2 (got '"$RC"')'; fi
check 'unknown topology: clear error' 'cannot resolve the reset target topology'
check 'unknown topology: observed list names jl-mysql' 'jl-mysql'

# --- 5. declared standalone with BOTH stacks up -> app switch wins -----------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis jl-mysql-router jl-mysql-1 jl-mysql-2 jl-mysql-3"
run_reset "$(make_env router)" --dry-run
check 'both-up router declaration keeps targeting the cluster' 'MySQL topology   : cluster'
run_reset "$(make_env standalone)" --dry-run
check 'both-up standalone declaration keeps jl-mysql' 'MySQL topology   : standalone'

# --- 6. sentinel redis -------------------------------------------------------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis-1 jl-redis-2 jl-redis-3"
export MOCK_REDIS_MASTER="jl-redis-2"
run_reset "$(make_env standalone sentinel)" --dry-run
if (( RC == 0 )); then pass 'sentinel dry-run exits 0'; else fail 'sentinel dry-run exits 0'; fi
check 'sentinel: every node is flushed' 'Redis targets    : jl-redis-1 jl-redis-2 jl-redis-3'
check 'sentinel: verification follows the master' 'verify on jl-redis-2'

# --- 7. --dry-run must not execute anything destructive ------------------------------------
if grep -qE -- 'docker compose .* (stop|start) ' "$TEST_ROOT/docker.log"; then
  fail 'dry-run never stops/starts the app tier'
else
  pass 'dry-run never stops/starts the app tier'
fi
if grep -q 'TRUNCATE' "$TEST_ROOT/docker.log"; then
  fail 'dry-run never runs TRUNCATE'
else
  pass 'dry-run never runs TRUNCATE'
fi

# --- 8. full mocked --yes run through the router route ---------------------------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis jl-mysql-router jl-mysql-1 jl-mysql-2 jl-mysql-3"
: >"$TEST_ROOT/docker.log"
run_reset "$(make_env router)" --yes
if (( RC == 0 )); then pass 'router --yes run exits 0'; else fail 'router --yes run exits 0'; fi
check 'router --yes: baseline passes' 'RESET PASSED'
check 'router --yes: TRUNCATE reached the cluster route' 'container=jl-mysql-1'
if grep -qE -- 'exec --env-file [^ ]+ jl-mysql-1 mysql -h mysql-router -P 6446.*TRUNCATE' "$TEST_ROOT/docker.log"; then
  pass 'router --yes: write SQL executed on a node via the Router'
else
  fail 'router --yes: write SQL executed on a node via the Router'
  grep TRUNCATE "$TEST_ROOT/docker.log" >&2 || true
fi

# --- 9. no --yes: interactive guard intact ---------------------------------------------------
export MOCK_RUNNING="$DEV_CONTAINERS jl-mysql jl-redis"
run_reset "$(make_env standalone)"
if [[ "$RC" == 2 ]] && grep -qF -- 'Re-run with --yes' <<<"$OUT"; then
  pass 'without --yes the guard still aborts (exit 2)'
else
  fail 'without --yes the guard still aborts (exit 2, got '"$RC"')'
fi

echo
if (( failures > 0 )); then
  echo "RESET-TOPOLOGY MOCK TESTS FAILED: $failures failure(s)" >&2
  exit 1
fi
echo "RESET-TOPOLOGY MOCK TESTS PASSED."
