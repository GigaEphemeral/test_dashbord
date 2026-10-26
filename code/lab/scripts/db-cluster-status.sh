#!/usr/bin/env bash
# Read-only status of the DB-HA tier (MySQL InnoDB Cluster + MySQL Router).
#
# What it prints, in this order:
#   1. container rollup  - state + health of each DB-HA container (docker inspect)
#   2. membership        - performance_schema.replication_group_members of every node
#   3. router routes     - which backend 6446 (read/write) and 6447 (read-only)
#                          currently resolve to, plus their read_only mode
#
# Hard rules (review gate):
#   * Purely read-only: only `docker inspect` and SELECT. No compose lifecycle
#     command, no DML/DDL, so it is safe to run during a demo or a chaos action.
#   * Never prints a credential. The password is expanded *inside* the container
#     from its own environment (`bash -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql ...'`),
#     so it never appears on a host command line - unlike `docker exec -e
#     MYSQL_PWD=$VALUE`, which puts the secret in argv and in the shell history.
#   * Every port, timeout, retry count, container name and account is an env
#     placeholder with a default (工程规约 §1); see .env.mysql.example.
#
# M3-2b contract: the two queries below are exactly the ~10s poll the app /
# chaos-controller must reuse. Their results become mysql_cluster_member_state
# (member_state per member) and mysql_cluster_primary_info (member_role='PRIMARY').
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
ENV_MYSQL_EXAMPLE="$DOCKER_DIR/.env.mysql.example"

# --- knobs (environment > .env > default) ------------------------------------
# Do not `source` the .env file (that would export every lab secret into this
# shell and into any child process); read only the keys needed here.
env_default() { # <key> <fallback>: value from the environment if set, else from .env
  local key="$1" fallback="$2" value=""
  if [[ -n "${!key:-}" ]]; then
    printf '%s' "${!key}"
    return 0
  fi
  if [[ -f "$ENV_FILE" ]]; then
    value="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true)"
    # strip a trailing CR (CRLF checkouts) and optional quotes
    value="${value%$'\r'}"
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
    if [[ -n "$value" ]]; then
      printf '%s' "$value"
      return 0
    fi
  fi
  printf '%s' "$fallback"
}

COMPOSE_PROJECT="$(env_default DB_CLUSTER_STATUS_PROJECT java-learn-lab)"
PREFIX="$(env_default DB_CLUSTER_STATUS_CONTAINER_PREFIX jl-)"
# Space-separated service names; must match compose.mysql.yml's node services.
NODES="$(env_default DB_CLUSTER_STATUS_NODES 'mysql-1 mysql-2 mysql-3')"
NODE_PORT="$(env_default MYSQL_HA_NODE_PORT 3306)"
ADMIN_USER="$(env_default MYSQL_HA_ADMIN_USER root)"
MYSQL_BIN="$(env_default MYSQL_HA_MYSQL_BIN mysql)"
# Name of the environment variable *inside* the node container carrying the admin
# password. Only the name is ever referenced on a command line.
ADMIN_PASSWORD_ENV="$(env_default DB_CLUSTER_STATUS_PASSWORD_ENV MYSQL_ROOT_PASSWORD)"
PROBE_TIMEOUT="$(env_default DB_CLUSTER_STATUS_TIMEOUT_SECONDS 5)"
PROBE_RETRIES="$(env_default DB_CLUSTER_STATUS_RETRIES 3)"
PROBE_INTERVAL="$(env_default DB_CLUSTER_STATUS_INTERVAL_SECONDS 2)"
CONNECT_TIMEOUT="$(env_default DB_CLUSTER_STATUS_CONNECT_TIMEOUT_SECONDS 5)"
ROUTER_PROBE="$(env_default DB_CLUSTER_STATUS_ROUTER_PROBE true)"
ROUTER_SERVICE="$(env_default MYSQL_HA_ROUTER_HOST mysql-router)"
ROUTER_RW_PORT="$(env_default MYSQL_HA_ROUTER_RW_PORT 6446)"
ROUTER_RO_PORT="$(env_default MYSQL_HA_ROUTER_RO_PORT 6447)"
# The route probe needs a mysql client; every cluster node image ships one, and
# the Router image is not guaranteed to. Overridable when the nodes are down and
# only the Router's own reachability matters.
PROBE_CONTAINER="$(env_default DB_CLUSTER_STATUS_PROBE_CONTAINER "${PREFIX}mysql-1")"

usage() {
  cat <<'EOF'
Usage: db-cluster-status.sh [--router] [--no-router] [--service NAME ...] [-h|--help]

Read-only MySQL InnoDB Cluster + Router status for the java-learn-lab DB-HA tier.
No credential is printed. Never starts, stops or recreates anything.

  --router       only probe the Router routes (6446/6447), skip per-node membership
  --no-router    skip the Router routes, print per-node membership only
  --service NAME restrict the membership section to these compose services
                 (repeatable, e.g. --service mysql-2)

Exit code: 0 when every node answers and the group has exactly one PRIMARY, 1 otherwise.
EOF
}

ONLY_ROUTER=false
ONLY_NODES=false
SELECTED_SERVICES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --router) ONLY_ROUTER=true; shift ;;
    --no-router) ONLY_NODES=true; shift ;;
    --service)
      if [[ $# -lt 2 ]]; then echo "ERROR: --service requires a value." >&2; exit 2; fi
      SELECTED_SERVICES+=("$2"); shift 2 ;;
    --service=*) SELECTED_SERVICES+=("${1#*=}"); shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker CLI not found in PATH." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running or not reachable." >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" && -f "$ENV_MYSQL_EXAMPLE" ]]; then
  printf 'WARN: %s is missing; lab defaults apply, so the DB-HA tier probe will use defaults.\n' \
    "$ENV_FILE" >&2
fi

# The exact membership view the monitoring poll reads (design §8.3: a ~10s poll).
# Kept as one statement so the app-side query in M3-2b is a copy of this, not a
# paraphrase: member_state -> mysql_cluster_member_state{member},
# member_role='PRIMARY' -> mysql_cluster_primary_info.
MEMBERSHIP_SQL="SELECT @@hostname, @@server_id, @@port, @@read_only, \
(SELECT COUNT(*) FROM performance_schema.replication_group_members), \
(SELECT GROUP_CONCAT(CONCAT(MEMBER_HOST,':',MEMBER_PORT,' ',MEMBER_STATE,' ',MEMBER_ROLE) \
ORDER BY MEMBER_ROLE DESC SEPARATOR ' | ') FROM performance_schema.replication_group_members)"

# Which instance a route currently resolves to. @@port separates the node behind
# the Router from the node the probe runs in.
ROUTE_SQL="SELECT @@hostname, @@port, @@read_only, @@server_id"

FAILURES=0
answers=0
primaries=0
node_total=0

container_state() { # <container> -> "running healthy" | "missing" | "exited no-healthcheck"
  local out=""
  if out="$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$1" 2>/dev/null | tr -d '\r')"; then
    printf '%s' "$out"
  else
    printf 'missing'
  fi
}

# mysql_query <container> <host> <port> <sql> -> tab-separated row, or non-zero.
# Retries are bounded by DB_CLUSTER_STATUS_RETRIES: a node mid-rejoin answers
# slowly, and a status script must not hang a demo.
mysql_query() {
  local container="$1" host="$2" port="$3" sql="$4"
  local attempt output="" remote
  # The password never reaches argv: bash inside the container expands
  # MYSQL_PWD from its own environment, then exec replaces bash with mysql.
  remote="MYSQL_PWD=\"\${${ADMIN_PASSWORD_ENV}:-}\" exec ${MYSQL_BIN} \
--connect-timeout=${CONNECT_TIMEOUT} --write-timeout=${PROBE_TIMEOUT} \
-N -B -h '${host}' -P '${port}' -u '${ADMIN_USER}'"
  for attempt in $(seq 1 "$PROBE_RETRIES"); do
    if output="$(docker exec "$container" bash -c "$remote" <<<"$sql" 2>&1)" \
       && [[ -n "$output" && "$output" == *$'\t'* ]]; then
      printf '%s\n' "$output" | tr -d '\r'
      return 0
    fi
    if (( attempt < PROBE_RETRIES )); then
      sleep "$PROBE_INTERVAL"
    fi
  done
  printf '%s\n' "${output:-no result}" | head -n 2 | tr -d '\r'
  return 1
}

echo "=== DB-HA container rollup (project: $COMPOSE_PROJECT) ==="
node_containers=()
if [[ "$ONLY_ROUTER" == false ]]; then
  # shellcheck disable=SC2086 # NODES is a space-separated list on purpose
  for service in $NODES; do
    if (( ${#SELECTED_SERVICES[@]} > 0 )); then
      if [[ ! " ${SELECTED_SERVICES[*]} " == *" $service "* ]]; then
        continue
      fi
    fi
    container="${PREFIX}${service}"
    node_containers+=("$container")
    node_total=$((node_total + 1))
    printf '%-16s %s\n' "$container" "$(container_state "$container")"
  done
else
  echo "skipped (--router)"
fi

if [[ "$ONLY_ROUTER" == false ]] && (( ${#node_containers[@]} > 0 )); then
  echo
  echo "=== group membership per node (performance_schema.replication_group_members) ==="
  printf '%-16s %-12s %-7s %-11s %-8s %s\n' \
    CONTAINER HOST PORT MODE MEMBERS 'MEMBERSHIP (host:port STATE ROLE)'
  for container in "${node_containers[@]}"; do
    if [[ "$(container_state "$container")" != running* ]]; then
      printf '%-16s %s\n' "$container" "not running - skipped"
      FAILURES=$((FAILURES + 1))
      continue
    fi
    if row="$(mysql_query "$container" 127.0.0.1 "$NODE_PORT" "$MEMBERSHIP_SQL")"; then
      IFS=$'\t' read -r host server_id port read_only member_count membership <<<"$row"
      mode='READ_ONLY'
      if [[ "$read_only" == "0" ]]; then
        mode='READ_WRITE'
      fi
      if [[ -z "$membership" || "$membership" == "NULL" ]]; then
        membership='EMPTY (no group on this node yet - run db-ha-up.sh)'
      fi
      printf '%-16s %-12s %-7s %-11s %-8s %s\n' \
        "$container" "$host" "$port" "$mode" "$member_count" "$membership"
      answers=$((answers + 1))
      # Each node reports its own view of the PRIMARY; take the max, not the sum.
      node_primaries="$(printf '%s' "$membership" | grep -o 'PRIMARY' | wc -l | tr -d ' ')"
      if (( node_primaries > primaries )); then
        primaries="$node_primaries"
      fi
    else
      printf '%-16s %s\n' "$container" "query failed: $(printf '%s' "${row:-no result}" | head -n 1)"
      FAILURES=$((FAILURES + 1))
    fi
  done
fi

if [[ "$ONLY_NODES" == false && "$ROUTER_PROBE" != false ]]; then
  echo
  echo "=== Router routes (via $ROUTER_SERVICE; host publishes 127.0.0.1 only) ==="
  probe_state="$(container_state "$PROBE_CONTAINER")"
  if [[ "$probe_state" != running* ]]; then
    echo "skipped: $PROBE_CONTAINER is $probe_state and the route probe needs its mysql client."
    echo "Hint: point DB_CLUSTER_STATUS_PROBE_CONTAINER at any running container with a mysql client."
    FAILURES=$((FAILURES + 1))
  else
    for route in "read/write:${ROUTER_RW_PORT}" "read-only:${ROUTER_RO_PORT}"; do
      label="${route%%:*}"
      port="${route##*:}"
      if row="$(mysql_query "$PROBE_CONTAINER" "$ROUTER_SERVICE" "$port" "$ROUTE_SQL")"; then
        IFS=$'\t' read -r host rport read_only server_id <<<"$row"
        mode='READ_ONLY'
        if [[ "$read_only" == "0" ]]; then
          mode='READ_WRITE'
        fi
        printf '%-10s :%-6s -> %-14s (server_id=%s, %s)\n' "$label" "$port" "$host" "$server_id" "$mode"
      else
        printf '%-10s :%-6s -> unavailable: %s\n' "$label" "$port" "$(printf '%s' "${row:-no result}" | head -n 1)"
        FAILURES=$((FAILURES + 1))
      fi
    done
  fi
fi

echo
if [[ "$ONLY_ROUTER" == true ]]; then
  echo "Router-only run; membership was not evaluated."
  if (( FAILURES > 0 )); then
    exit 1
  fi
  exit 0
fi

if (( answers == 0 )); then
  echo "RESULT: no node answered. Is the tier up? bash $SCRIPT_DIR/db-ha-up.sh" >&2
  exit 1
fi
if (( primaries == 1 )) && (( FAILURES == 0 )) && (( answers == node_total )); then
  echo "RESULT: healthy - $answers/$node_total node(s) answering, exactly one PRIMARY."
  exit 0
fi
echo "RESULT: degraded - answering=$answers/$node_total, PRIMARY count=$primaries, failures=$FAILURES."
echo "A missing PRIMARY, two PRIMARY rows or a member in RECOVERING/ERROR means the"
echo "group is forming, rejoining or lost quorum; check 'docker logs jl-mysql-cluster-init'"
echo "and the runbook in doc/process/step-25a-mysql-cluster-compose.md."
exit 1
