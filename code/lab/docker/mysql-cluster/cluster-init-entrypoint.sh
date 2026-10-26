#!/usr/bin/env bash
# lab-mysql-cluster initialisation job (compose service mysql-cluster-init).
#
# One-shot container that:
#   1. waits until mysqld answers on all three nodes (bounded retries)
#   2. runs create-cluster.js (MySQL Shell AdminAPI: create + addInstance + wait)
#   3. finds the elected PRIMARY and applies init/00-cluster-accounts.sql.tpl on it
#   4. prints a membership summary and exits
# Exit code 0 means "cluster ONLINE with MYSQL_HA_EXPECTED_MEMBERS members and the
# lab accounts present", which is what mysql-router-init and mysql-router gate on
# (depends_on: service_completed_successfully).
#
# Idempotent by design: re-running it against an existing cluster only adds missing
# members and re-applies IF NOT EXISTS grants, so a failed first attempt on Docker
# Desktop can simply be retried (design risk "InnoDB Cluster 在 Docker Desktop
# 初始化不稳").
set -euo pipefail

ASSET_DIR="${MYSQL_HA_ASSET_DIR:?MYSQL_HA_ASSET_DIR must be set by compose}"
# shellcheck disable=SC1090 # path is an env placeholder on purpose
source "$ASSET_DIR/lib/render-tpl.sh"

require_env \
  MYSQL_ROOT_PASSWORD \
  MYSQL_HA_MYSQL_1_HOST \
  MYSQL_HA_MYSQL_2_HOST \
  MYSQL_HA_MYSQL_3_HOST \
  MYSQL_HA_NODE_PORT \
  MYSQL_HA_INIT_RETRY_COUNT \
  MYSQL_HA_INIT_RETRY_INTERVAL_SECONDS \
  MYSQL_HA_EXPORTER_USER \
  MYSQL_HA_EXPORTER_PASSWORD \
  MYSQL_HA_MONITOR_USER \
  MYSQL_HA_MONITOR_PASSWORD \
  MYSQL_HA_ACCOUNT_HOST_PATTERN \
  MYSQL_USER \
  MYSQL_PASSWORD \
  MYSQL_DATABASE \
  MYSQL_HA_INIT_TMP_DIR

MYSQLSH_BIN="${MYSQL_HA_MYSQLSH_BIN:-mysqlsh}"
MYSQL_BIN="${MYSQL_HA_MYSQL_BIN:-mysql}"
ADMIN_USER="${MYSQL_HA_ADMIN_USER:-root}"
EXEC_TIMEOUT="${MYSQL_HA_PROBE_TIMEOUT_SECONDS:-5}"
NODES="${MYSQL_HA_MYSQL_1_HOST} ${MYSQL_HA_MYSQL_2_HOST} ${MYSQL_HA_MYSQL_3_HOST}"
# Created before the first probe: mysql_as writes its stderr file here, and a
# missing directory would turn every probe into a false negative.
mkdir -p "$MYSQL_HA_INIT_TMP_DIR"

log() { printf '[cluster-init] %s\n' "$*"; }
err() { printf '[cluster-init] ERROR: %s\n' "$*" >&2; }
# Verbose client stderr is gated: the wait loop would otherwise print one line per
# node per attempt while the nodes are still doing their first initdb. Enable with
# MYSQL_HA_INIT_DEBUG=true when a probe fails for a reason the message does not say.
DEBUG="${MYSQL_HA_INIT_DEBUG:-false}"
debug() {
  if [[ "$DEBUG" == true ]]; then
    printf '[cluster-init] debug: %s\n' "$*" >&2
  fi
}

# mysql_as <host> <sql> - runs a query as the admin user with the password passed
# through MYSQL_PWD (never argv, never printed, never exported to the parent
# shell: it is a command-scoped assignment so only the client sees it).
# Client stderr goes to a file instead of /dev/null: "unknown variable", "access
# denied" and "connection refused" are three different remedies, and swallowing
# them would make the first live run undebuggable. MySQL never echoes the password.
mysql_as() {
  local host="$1" sql="$2" err_file stderr_text
  err_file="${MYSQL_HA_INIT_TMP_DIR:-/tmp}/mysql_as.err.$$"
  if printf '%s' "$sql" | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" timeout "$EXEC_TIMEOUT" \
      "$MYSQL_BIN" --host="$host" --port="$MYSQL_HA_NODE_PORT" \
      --user="$ADMIN_USER" --connect-timeout="$EXEC_TIMEOUT" --protocol=TCP \
      -N -B 2>"$err_file"; then
    rm -f "$err_file"
    return 0
  fi
  stderr_text="$(head -n 3 "$err_file" 2>/dev/null | tr '\n' ' ' || true)"
  rm -f "$err_file"
  debug "mysql@$host:$MYSQL_HA_NODE_PORT ${stderr_text:-no stderr}"
  return 1
}

wait_for_nodes() {
  local attempt host last="" node_count
  # shellcheck disable=SC2086 # NODES is a deliberate space-separated host list
  node_count="$(printf '%s\n' $NODES | wc -l | tr -d ' ')"
  for attempt in $(seq 1 "$MYSQL_HA_INIT_RETRY_COUNT"); do
    local ready=0
    # shellcheck disable=SC2086 # NODES is a deliberate space-separated host list
    for host in $NODES; do
      if [[ "$(mysql_as "$host" 'SELECT 1;' || true)" == "1" ]]; then
        ready=$((ready + 1))
      fi
    done
    if [[ "$ready" -eq "$node_count" ]]; then
      log "all nodes answer on port $MYSQL_HA_NODE_PORT (attempt $attempt)"
      return 0
    fi
    last="ready=$ready"
    log "waiting for mysqld ($last, attempt $attempt/$MYSQL_HA_INIT_RETRY_COUNT); retry in ${MYSQL_HA_INIT_RETRY_INTERVAL_SECONDS}s"
    sleep "$MYSQL_HA_INIT_RETRY_INTERVAL_SECONDS"
  done
  err "nodes never all answered within $MYSQL_HA_INIT_RETRY_COUNT attempts ($last)."
  err "diagnose: docker compose ... logs mysql-1 mysql-2 mysql-3 | tail -50"
  err "most common causes: (a) unknown variable in the rendered option file,"
  err "(b) an old volume initialised with gtid_mode=OFF (cluster nodes need their"
  err "own volumes - never point a cluster node at jl-mysql-data),"
  err "(c) root@'%' missing, i.e. MYSQL_HA_ROOT_HOST was overridden."
  return 1
}

# mysqlsh has no `process`, so create-cluster.js reads config/credentials from a
# generated global LAB_ENV object instead. This dumps the whole environment into a
# 0600 JS object literal (JSON-escaped); it lives only in the ephemeral tmp dir and
# is removed in cleanup_init_secrets(). Nothing goes on argv and no value is printed.
write_lab_env() {
  local out="$1" k v esc
  umask 077
  printf '// generated by cluster-init-entrypoint.sh - 0600, transient\n' >"$out"
  printf 'var LAB_ENV = {\n' >>"$out"
  while IFS='=' read -r k v; do
    # only keys mysqlsh can index as "KEY"; skip malformed / special lines
    [[ "$k" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    # escape backslash then double-quote for a JS string literal
    esc="${v//\\/\\\\}"; esc="${esc//\"/\\\"}"
    printf '  "%s": "%s",\n' "$k" "$esc" >>"$out"
  done < <(env)
  printf '};\n' >>"$out"
}

# setup_launch_auth: mysqlsh 8.4.4 authenticates a CLI --uri connection from the
# standard MySQL client option files, so we drop a 0600 ~/.my.cnf [client] password
# here. That makes the launch connection succeed WITHOUT putting the password on
# argv. (In-script connections to the other nodes take the password from LAB_ENV,
# because a per-process option file is not consulted for those.)
setup_launch_auth() {
  local f="${HOME:-/root}/.my.cnf"
  umask 077
  printf '[client]\npassword=%s\n' "$MYSQL_ROOT_PASSWORD" >"$f"
  printf '%s' "$f"
}

cleanup_init_secrets() {
  rm -f "${MYSQL_HA_INIT_TMP_DIR}/lab-env.js" "${HOME:-/root}/.my.cnf" 2>/dev/null || true
}

run_shell_bootstrap() {
  local seed_uri labenv
  if ! command -v "$MYSQLSH_BIN" >/dev/null 2>&1; then
    err "$MYSQLSH_BIN not found in the init image. The ${MYSQL_IMAGE:-mysql} 8.4 image"
    err "ships MySQL Shell (layer 'microdnf install mysql-shell-<ver>'); if you switched"
    err "MYSQL_IMAGE to a slim flavour, set MYSQL_HA_INIT_IMAGE to a shell-capable image."
    return 1
  fi
  log "bootstrapping cluster with $("$MYSQLSH_BIN" --version 2>/dev/null | head -n 1)"
  setup_launch_auth >/dev/null
  labenv="${MYSQL_HA_INIT_TMP_DIR}/lab-env.js"
  write_lab_env "$labenv"
  # NB: mysqlsh has no `--no-log` option; logging is steered with --log-file, so it
  # goes to /dev/null. --mc forces classic protocol (the docker-library image does
  # not run the X plugin); --uri authenticates from ~/.my.cnf (no argv password) and
  # suppresses mysqlsh's eager root@localhost probe; -e loads lab-env.js then the
  # bootstrap script, so credentials reach the script only via the 0600 lab-env.js.
  seed_uri="${ADMIN_USER}@${MYSQL_HA_MYSQL_1_HOST}:${MYSQL_HA_NODE_PORT}"
  "$MYSQLSH_BIN" --js --mc --log-file=/dev/null --uri="$seed_uri" \
    -e "load('${labenv}'); load('${ASSET_DIR}/create-cluster.js');"
}

# find_primary - the elected write target, from the membership view every node
# publishes. This is the same query the chaos-controller probe uses (M3-2b), so the
# contract is defined here once.
find_primary() {
  local host state
  # shellcheck disable=SC2086 # NODES is a deliberate space-separated host list
  for host in $NODES; do
    state="$(mysql_as "$host" \
      "SELECT member_host FROM performance_schema.replication_group_members
        WHERE member_role='PRIMARY' AND member_state='ONLINE' LIMIT 1;" || true)"
    if [[ -n "$state" ]]; then
      printf '%s\n' "$state"
      return 0
    fi
  done
  return 1
}

apply_grants() {
  local primary="$1" rendered
  rendered="$MYSQL_HA_INIT_TMP_DIR/00-cluster-accounts.sql"
  mkdir -p "$MYSQL_HA_INIT_TMP_DIR"
  render_template "$ASSET_DIR/init/00-cluster-accounts.sql.tpl" "$rendered"
  # Applied on the primary only: the statements replicate to the group instead of
  # creating a second GTID stream on each node.
  if ! mysql_as "$primary" "$(cat "$rendered")" >/dev/null; then
    err "grant statements failed on primary $primary."
    return 1
  fi
  log "lab accounts applied on primary $primary (exporter + monitor + app)"
}

print_membership() {
  local host rows
  # shellcheck disable=SC2086 # NODES is a deliberate space-separated host list
  for host in $NODES; do
    rows="$(mysql_as "$host" \
      "SELECT CONCAT(member_host,':',member_port,' ',member_role,' ',member_state)
         FROM performance_schema.replication_group_members;" || true)"
    log "membership as seen by $host:"
    if [[ -n "$rows" ]]; then
      printf '%s\n' "$rows" | sed 's/^/    /'
    else
      printf '    <no rows: group not formed on this node>\n'
    fi
  done
}

main() {
  if [[ ! -d "$ASSET_DIR" ]]; then
    err "asset directory $ASSET_DIR is not mounted (check the compose volume for mysql-cluster/)."
    exit 1
  fi
  # Remove the transient 0600 credential files (~/.my.cnf, lab-env.js) on every exit.
  trap cleanup_init_secrets EXIT
  log "cluster=${MYSQL_HA_CLUSTER_NAME:-labCluster} nodes=$NODES"
  wait_for_nodes
  run_shell_bootstrap
  local attempt primary=""
  for attempt in $(seq 1 "$MYSQL_HA_INIT_RETRY_COUNT"); do
    primary="$(find_primary || true)"
    [[ -n "$primary" ]] && break
    log "no ONLINE PRIMARY yet (attempt $attempt/$MYSQL_HA_INIT_RETRY_COUNT)"
    sleep "$MYSQL_HA_INIT_RETRY_INTERVAL_SECONDS"
  done
  if [[ -z "$primary" ]]; then
    print_membership
    err "no ONLINE PRIMARY within the retry budget; the cluster did not form."
    exit 1
  fi
  apply_grants "$primary"
  print_membership
  log "BOOTSTRAP COMPLETE - ready for mysql-router-init"
}

main "$@"
