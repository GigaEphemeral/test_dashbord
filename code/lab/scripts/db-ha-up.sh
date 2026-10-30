#!/usr/bin/env bash
# Bring up the DB-HA tier only: MySQL InnoDB Cluster (3 Group Replication nodes,
# single primary) + MySQL Router (6446 read/write, 6447 read-only) + one
# mysqld-exporter per node, plus the two one-shot bootstrap jobs.
#
# Why this exists next to up.sh:
#   * up.sh owns the always-on dev stack (16 containers) and must not grow a
#     dependency on a cluster declared at 2.1GB (~1.87GB measured); design §3.1
#     budgets the cluster for the hours when the app tier is idle.
#   * the cluster needs an ordered bootstrap the declarative model cannot express
#     on its own (nodes healthy -> dba.createCluster -> Router metadata cache),
#     so each step is a separate `compose up -d` with an explicit bounded wait.
#   * every wait has a configurable timeout and poll interval, so a half-formed
#     group fails with a message instead of hanging a session.
#
# Idempotent by design: re-running this script on a healthy cluster is a no-op
# (create-cluster.js reuses the existing cluster, router-bootstrap-entrypoint.sh
# skips when mysqlrouter.conf exists).
#
# It never prints a credential: only key names are inspected, and .env is not
# sourced.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
ENV_EXAMPLE="$DOCKER_DIR/.env.example"
ENV_MYSQL_EXAMPLE="$DOCKER_DIR/.env.mysql.example"
BASE_COMPOSE="$DOCKER_DIR/compose.base.yml"
DEV_COMPOSE="$DOCKER_DIR/compose.dev.yml"
MYSQL_COMPOSE="$DOCKER_DIR/compose.mysql.yml"
STATUS_SCRIPT="$SCRIPT_DIR/db-cluster-status.sh"

# --- knobs (environment > .env > default) ------------------------------------
# .env is deliberately NOT sourced: it holds every lab credential, and exporting
# it would push those values into the environment of every child process (and
# into any `docker inspect`/`ps` output that dumps env). Only key names are read.
env_default() { # <key> <fallback>
  local key="$1" fallback="$2" value=""
  if [[ -n "${!key:-}" ]]; then
    printf '%s' "${!key}"
    return 0
  fi
  if [[ -f "$ENV_FILE" ]]; then
    value="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true)"
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

COMPOSE_PROJECT="$(env_default DB_HA_COMPOSE_PROJECT java-learn-lab)"
PROFILE="$(env_default DB_HA_PROFILE db-ha)"
# Space-separated lists; turned into arrays below so they are always word-split
# on our own terms (SC2086-clean).
NODES_LIST="$(env_default DB_HA_NODES 'mysql-1 mysql-2 mysql-3')"
EXPORTERS_LIST="$(env_default DB_HA_EXPORTERS 'mysql-1-exporter mysql-2-exporter mysql-3-exporter')"
CLUSTER_INIT_SERVICE="$(env_default DB_HA_CLUSTER_INIT_SERVICE mysql-cluster-init)"
ROUTER_INIT_SERVICE="$(env_default DB_HA_ROUTER_INIT_SERVICE mysql-router-init)"
ROUTER_SERVICE="$(env_default DB_HA_ROUTER_SERVICE mysql-router)"
PREFIX="$(env_default DB_HA_CONTAINER_PREFIX jl-)"
# Keys the compose layer cannot default for: MYSQL_HA_CLUSTER_GROUP_NAME is the
# Group Replication UUID every node must agree on, so it must come from .env.
REQUIRED_KEYS="$(env_default DB_HA_REQUIRED_KEYS 'MYSQL_HA_CLUSTER_GROUP_NAME')"
NODE_WAIT_TIMEOUT="$(env_default DB_HA_NODE_WAIT_TIMEOUT_SECONDS 240)"
INIT_WAIT_TIMEOUT="$(env_default DB_HA_INIT_WAIT_TIMEOUT_SECONDS 300)"
INIT_POLL_INTERVAL="$(env_default DB_HA_INIT_POLL_INTERVAL_SECONDS 5)"
HEALTH_WAIT_TIMEOUT="$(env_default DB_HA_CLUSTER_HEALTH_TIMEOUT_SECONDS 120)"
HEALTH_POLL_INTERVAL="$(env_default DB_HA_CLUSTER_HEALTH_INTERVAL_SECONDS 5)"
MEMORY_BUDGET_WARN="$(env_default DB_HA_MEMORY_BUDGET_WARN true)"
LOG_TAIL="$(env_default DB_HA_LOG_TAIL_LINES 40)"
ROUTER_RW_HOST_PORT="$(env_default MYSQL_HA_ROUTER_RW_HOST_PORT 6446)"
ROUTER_RO_HOST_PORT="$(env_default MYSQL_HA_ROUTER_RO_HOST_PORT 6447)"
ROUTER_RW_PORT="$(env_default MYSQL_HA_ROUTER_RW_PORT 6446)"
ROUTER_RO_PORT="$(env_default MYSQL_HA_ROUTER_RO_PORT 6447)"
EXPORTER_PORT="$(env_default MYSQL_HA_EXPORTER_PORT 9104)"
APP_DATABASE="$(env_default MYSQL_DATABASE seckill)"

usage() {
  cat <<'EOF'
Usage: db-ha-up.sh [--profile db-ha|ha] [--skip-router] [-h|--help]

Start the java-learn-lab DB-HA tier: 3 MySQL 8.4 Group Replication nodes, the
cluster bootstrap job, MySQL Router (6446 RW / 6447 RO) and one exporter per node.
Needs the dev stack's .env (run up.sh first, or keep an existing .env).

  --profile db-ha   this tier alone (default; what the memory budget allows while
                    the always-on dev stack keeps running)
  --profile ha      the design §3.3 umbrella profile, so compose.ha.yml's Redis
                    Sentinel tier and this one start together
  --skip-router     nodes + cluster only; skip the Router tier (no MySQL Router
                    image pull needed - useful when the registry mirror is down)
  -h, --help        this message

Waits are configurable: DB_HA_NODE_WAIT_TIMEOUT_SECONDS,
DB_HA_INIT_WAIT_TIMEOUT_SECONDS, DB_HA_CLUSTER_HEALTH_TIMEOUT_SECONDS and the
matching *_POLL_INTERVAL keys (see .env.mysql.example).
EOF
}

SKIP_ROUTER=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      if [[ $# -lt 2 ]]; then echo "ERROR: --profile requires a value (db-ha|ha)." >&2; exit 2; fi
      PROFILE="$2"; shift 2 ;;
    --profile=*) PROFILE="${1#*=}"; shift ;;
    --skip-router) SKIP_ROUTER=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$PROFILE" in
  db-ha|ha) ;;
  *) echo "ERROR: unknown profile '$PROFILE'. Use 'db-ha' (this tier) or 'ha' (design umbrella)." >&2
     exit 2 ;;
esac

die() {
  printf 'ERROR: %s\n' "$1" >&2
  shift
  # Each remaining argument is a diagnosis hint for the pitfall the caller just
  # hit; these messages are the reason this script exists.
  for hint in "$@"; do
    printf '  - %s\n' "$hint" >&2
  done
  exit 1
}

# --- preflight ---------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  die "docker CLI not found in PATH." "Install Docker Desktop and make sure it is in PATH."
fi
if ! docker info >/dev/null 2>&1; then
  die "Docker is not running or not reachable." "Start Docker Desktop, then retry."
fi

for required in "$BASE_COMPOSE" "$DEV_COMPOSE" "$MYSQL_COMPOSE" "$ENV_MYSQL_EXAMPLE"; do
  [[ -f "$required" ]] || die "missing $required" "This tier is an overlay on the dev stack."
done

if [[ ! -f "$ENV_FILE" ]]; then
  if [[ -f "$ENV_EXAMPLE" ]]; then
    printf 'WARN: %s is missing; using .env.example for variable resolution.\n' "$ENV_FILE" >&2
    printf 'WARN: the lab credentials will then be the example ones - fine for a demo, not for a shared machine.\n' >&2
    ENV_FILE="$ENV_EXAMPLE"
  else
    die "neither $ENV_FILE nor $ENV_EXAMPLE exists." "Run 'bash $SCRIPT_DIR/up.sh --profile dev' once."
  fi
fi

# Key-name audit (names only, never values).
missing_keys=()
while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
  line="${raw_line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*$ ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  if [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*= ]]; then
    key="${BASH_REMATCH[1]}"
    if ! grep -Eq "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE"; then
      missing_keys+=("$key")
    fi
  fi
done < "$ENV_MYSQL_EXAMPLE"

if (( ${#missing_keys[@]} > 0 )); then
  printf 'WARN: %s does not define %d DB-HA key(s); the compose defaults will apply:\n' \
    "$ENV_FILE" "${#missing_keys[@]}" >&2
  printf '  - %s\n' "${missing_keys[@]}" >&2
  printf 'To pin them explicitly: grep -E "^[A-Z]" %s >> %s   (then edit the values)\n' \
    "$ENV_MYSQL_EXAMPLE" "$ENV_FILE" >&2
fi

required_list=()
read -r -a required_list <<<"$REQUIRED_KEYS"
for key in "${required_list[@]}"; do
  value="$(env_default "$key" '')"
  if [[ -z "$value" ]]; then
    die "$key is not set (in the environment or in $ENV_FILE)." \
      "It is the Group Replication UUID every node must agree on, so it has no default." \
      "Generate one: uuidgen (Git Bash) or a UUID v1/v4 from any tool." \
      "Then add MYSQL_HA_CLUSTER_GROUP_NAME=<uuid> to $ENV_FILE."
  fi
  if [[ ! "$value" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
    die "$key is not a UUID (got a value of length ${#value}; expected 8-4-4-4-12 hex digits)." \
      "Group Replication rejects any other group name, and mysqld would fail at start."
  fi
done

NODES=()
EXPORTERS=()
read -r -a NODES <<<"$NODES_LIST"
read -r -a EXPORTERS <<<"$EXPORTERS_LIST"

compose() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$BASE_COMPOSE" \
    -f "$DEV_COMPOSE" \
    -f "$MYSQL_COMPOSE" \
    --profile "$PROFILE" \
    "$@"
}

# The same model with no profile selected: proves the always-on service list is
# untouched by this overlay (pure parse, no lifecycle).
compose_never() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$BASE_COMPOSE" \
    -f "$DEV_COMPOSE" \
    -f "$MYSQL_COMPOSE" \
    "$@"
}

print_diagnostics() {
  echo "--- docker compose --profile $PROFILE ps -a (DB-HA tier) ---" >&2
  compose ps -a "${NODES[@]}" "$CLUSTER_INIT_SERVICE" "$ROUTER_INIT_SERVICE" \
    "$ROUTER_SERVICE" "${EXPORTERS[@]}" >&2 || true
  echo "--- last $LOG_TAIL lines of the node logs ---" >&2
  compose logs --tail="$LOG_TAIL" "${NODES[@]}" >&2 || true
}

# Memory budget note (design §3.1). Informational: the constraint is the host, not
# Docker, so this warns instead of refusing.
if [[ "$MEMORY_BUDGET_WARN" != false ]]; then
  running="$(docker ps -q --filter "label=lab=java-learn" | wc -l | tr -d ' ')"
  echo "DB-HA plan: ${#NODES[@]} nodes (mem_limit $(env_default MYSQL_HA_NODE_MEM_LIMIT 640m) each), ${#EXPORTERS[@]} exporters, 1 Router."
  if (( running > 0 )); then
    printf 'NOTE: %s lab container(s) are already running. The cluster is declared at 2.1GB (~1.87GB measured) on\n' "$running"
    echo "      top of them; if Docker Desktop starts swapping, stop the app tier first - the"
    echo "      DB-HA tier does not need it (docker compose ... stop app-1 app-2 app-3)."
  fi
fi

# Show what this profile owns, taken from the rendered model rather than from a
# hard-coded list, so the two can never drift apart.
always_on="$(compose_never config --services 2>/dev/null | sort)"
with_profile="$(compose config --services 2>/dev/null | sort)"
db_ha_services="$(comm -13 <(printf '%s\n' "$always_on") <(printf '%s\n' "$with_profile"))"
if [[ -z "$db_ha_services" ]]; then
  die "the DB-HA layer rendered no extra services." \
    "Is $MYSQL_COMPOSE in the file list, and does its profiles: match '$PROFILE'?"
fi
echo "Always-on services: $(printf '%s\n' "$always_on" | wc -l | tr -d ' ') (unchanged by this tier)."
echo "DB-HA services this run owns:"
while IFS= read -r service; do
  printf '  - %s\n' "$service"
done <<<"$db_ha_services"

container_state() {
  local out=""
  if out="$(docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null | tr -d '\r')"; then
    printf '%s' "$out"
  else
    printf 'missing'
  fi
}

# wait_for_exit <container> <timeout> <interval> -> echoes the exit code
wait_for_exit() {
  local container="$1" timeout="$2" interval="$3"
  local deadline now state code
  deadline=$(( $(date +%s) + timeout ))
  while true; do
    state="$(container_state "$container")"
    if [[ "$state" == "exited" || "$state" == "dead" ]]; then
      code="$(docker inspect -f '{{.State.ExitCode}}' "$container" 2>/dev/null | tr -d '\r' || echo '?')"
      printf '%s' "$code"
      return 0
    fi
    if [[ "$state" == "missing" ]]; then
      printf 'missing'
      return 1
    fi
    now="$(date +%s)"
    if (( now > deadline )); then
      return 1
    fi
    sleep "$interval"
  done
}

# --- 1. nodes ----------------------------------------------------------------
echo "[1/5] Starting the Group Replication nodes: ${NODES[*]}"
if ! compose up -d --wait --wait-timeout "$NODE_WAIT_TIMEOUT" "${NODES[@]}"; then
  print_diagnostics
  die "the MySQL nodes did not become healthy within ${NODE_WAIT_TIMEOUT}s." \
    "The compose model parsed, so this is a runtime problem: start with 'docker logs jl-mysql-1'." \
    "Unknown option-file variable => conf/node.cnf.tpl drifted from 8.4; node-entrypoint.sh already printed a WARN from 'mysqld --validate-config'." \
    "gtid_mode=OFF on an existing volume => that volume predates these settings: db-ha-down.sh --purge (deletes cluster data only, never the dev volume)." \
    "OOM => lower MYSQL_HA_NODE_MEM_LIMIT / MYSQL_HA_INNODB_BUFFER_POOL_SIZE, or stop the app tier."
fi
echo "Nodes are healthy (mysqladmin ping)."

# --- 2. cluster bootstrap ----------------------------------------------------
echo "[2/5] Running the cluster bootstrap job: $CLUSTER_INIT_SERVICE"
if ! compose up -d "$CLUSTER_INIT_SERVICE"; then
  print_diagnostics
  die "could not start $CLUSTER_INIT_SERVICE." \
    "MYSQL_HA_INIT_IMAGE defaults to MYSQL_IMAGE (mysql:8.4.4, which ships MySQL Shell); confirm the image the model renders with 'docker compose ... --profile $PROFILE config' and check it exists locally with 'docker images'."
fi
init_container="${PREFIX}${CLUSTER_INIT_SERVICE}"
if ! exit_code="$(wait_for_exit "$init_container" "$INIT_WAIT_TIMEOUT" "$INIT_POLL_INTERVAL")"; then
  printf 'ERROR: %s did not exit within %ss (state=%s).\n' \
    "$init_container" "$INIT_WAIT_TIMEOUT" "$(container_state "$init_container")" >&2
  echo "--- last $LOG_TAIL lines ---" >&2
  docker logs --tail="$LOG_TAIL" "$init_container" 2>&1 || true
  die "the bootstrap job is stuck." \
    "A member stuck in RECOVERING is usually a clone transfer: raise DB_HA_INIT_WAIT_TIMEOUT_SECONDS on slow disks." \
    "The job polls ONLINE members itself - MYSQL_HA_CLUSTER_READY_TIMEOUT_SECONDS."
fi
if [[ "$exit_code" != "0" ]]; then
  echo "--- last $LOG_TAIL lines of $init_container ---" >&2
  docker logs --tail="$LOG_TAIL" "$init_container" 2>&1 || true
  die "$init_container exited with code $exit_code." \
    "Seed unreachable => mysqld on the first node is not healthy yet; check its log." \
    "Docker Desktop pitfall: report_host and group_replication_local_address MUST be compose service names (mysql-1..3); IPs change on recreate and GR rejoin then fails." \
    "addInstance rejected => errant transactions from each node's own initdb; MYSQL_HA_RECOVERY_METHOD=clone (the default) cures it - just re-run this script." \
    "Two seeds => once a group exists, bootstrap must target the existing seed only; create-cluster.js handles that with dba.getCluster()."
fi
echo "Bootstrap job finished cleanly (exit 0)."

# --- 3. Router ---------------------------------------------------------------
if [[ "$SKIP_ROUTER" == false ]]; then
  echo "[3/5] Bootstrapping and starting MySQL Router: $ROUTER_SERVICE"
  if ! compose up -d --wait --wait-timeout "$NODE_WAIT_TIMEOUT" "$ROUTER_SERVICE"; then
    print_diagnostics
    router_init_container="${PREFIX}${ROUTER_INIT_SERVICE}"
    if [[ -n "$(docker ps -aq --filter "name=${router_init_container}$" 2>/dev/null)" ]]; then
      echo "--- last $LOG_TAIL lines of $router_init_container ---" >&2
      docker logs --tail="$LOG_TAIL" "$router_init_container" 2>&1 || true
    fi
    die "the Router tier did not become healthy within ${NODE_WAIT_TIMEOUT}s." \
      "Image pull failed => the registry mirror may not serve the Router tag; set MYSQL_HA_ROUTER_IMAGE to a tag present on this machine, or use --skip-router." \
      "Metadata missing => Router bootstrap must run AFTER the cluster metadata exists; read the step [2/5] log first." \
      "Data directory not writable => preflight_data_dir in router-bootstrap-entrypoint.sh prints the uid, the ls -ld output and the exact remedy."
  fi
else
  echo "[3/5] Skipped (--skip-router): no 6446/6447 endpoint."
fi

# --- 4. exporters ------------------------------------------------------------
echo "[4/5] Starting one mysqld-exporter per node: ${EXPORTERS[*]}"
if ! compose up -d "${EXPORTERS[@]}"; then
  print_diagnostics
  die "the per-node exporters did not start." \
    "They reuse MYSQLD_EXPORTER_IMAGE (no new pull); 'Access denied' means the exporter account was not created on the primary during step [2/5]."
fi

# --- 5. cluster health -------------------------------------------------------
echo "[5/5] Waiting up to ${HEALTH_WAIT_TIMEOUT}s for the group to report healthy..."
health_deadline=$(( $(date +%s) + HEALTH_WAIT_TIMEOUT ))
health_out=""
until health_out="$("$STATUS_SCRIPT" 2>&1)"; do
  if (( $(date +%s) >= health_deadline )); then
    printf '%s\n' "$health_out" >&2
    die "the cluster did not report a single PRIMARY plus ONLINE members in ${HEALTH_WAIT_TIMEOUT}s." \
      "Run 'bash $STATUS_SCRIPT' for the live membership and 'docker logs jl-mysql-2' for a node that cannot rejoin." \
      "A member in ERROR after a restart is the classic Docker Desktop case: the container kept its old IP - delete the member's volume or re-run this script so the option file is re-rendered."
  fi
  sleep "$HEALTH_POLL_INTERVAL"
done
printf '%s\n' "$health_out"

echo
echo "DB-HA tier is up (profile: $PROFILE):"
echo "  read/write : 127.0.0.1:${ROUTER_RW_HOST_PORT} -> jdbc:mysql://mysql-router:${ROUTER_RW_PORT}/${APP_DATABASE} (follows the PRIMARY)"
echo "  read-only  : 127.0.0.1:${ROUTER_RO_HOST_PORT} -> jdbc:mysql://mysql-router:${ROUTER_RO_PORT}/${APP_DATABASE} (spreads over ONLINE secondaries)"
echo "  nodes      : internal to jl-net only; no host port is published for them"
echo "  exporters  : :${EXPORTER_PORT} per node (Prometheus scrape jobs: wiring TODO in step-25a)"
echo
echo "Status any time: bash $STATUS_SCRIPT"
echo "Tear it down with: bash $SCRIPT_DIR/db-ha-down.sh   (add --purge to delete cluster volumes)"
