#!/usr/bin/env bash
# lab-mysql-router bootstrap job (compose service mysql-router-init).
#
# One-shot container, gated behind mysql-cluster-init completing successfully, that
# generates mysqlrouter.conf into a shared named volume; the long-running
# mysql-router service then only reads that config. Order matters: bootstrapping
# the Router before the cluster metadata schema exists is the second classic
# Docker/InnoDB-Cluster mistake (it either fails, or produces a Router with zero
# routing destinations that looks healthy but routes nothing).
#
# Idempotent: an existing config is kept unless MYSQL_HA_ROUTER_FORCE_BOOTSTRAP is
# true, so re-running db-ha-up.sh never rewrites a working Router.
#
# Nothing here prints a password. The generated mysqlrouter.conf stores its
# metadata-cache credentials in the keyring file next to it, not in plain text.
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
  MYSQL_HA_ROUTER_DATA_DIR \
  MYSQL_HA_ROUTER_CONF_NAME \
  MYSQL_HA_ROUTER_NAME \
  MYSQL_HA_ROUTER_LOG_LEVEL \
  MYSQL_HA_ROUTER_SSL_MODE \
  MYSQL_HA_ROUTER_USER \
  MYSQL_HA_ROUTER_PASSWORD_MODE \
  MYSQL_HA_ROUTER_RW_PORT \
  MYSQL_HA_ROUTER_RO_PORT \
  MYSQL_HA_INIT_RETRY_COUNT \
  MYSQL_HA_INIT_RETRY_INTERVAL_SECONDS \
  MYSQL_HA_PROBE_TIMEOUT_SECONDS

MYSQL_BIN="${MYSQL_HA_MYSQL_BIN:-mysql}"
MYSQLROUTER_BIN="${MYSQL_HA_MYSQLROUTER_BIN:-mysqlrouter}"
ADMIN_USER="${MYSQL_HA_ADMIN_USER:-root}"
SEED_HOST="${MYSQL_HA_MYSQL_1_HOST}"
NODES="${MYSQL_HA_MYSQL_1_HOST} ${MYSQL_HA_MYSQL_2_HOST} ${MYSQL_HA_MYSQL_3_HOST}"
CONF_PATH="${MYSQL_HA_ROUTER_DATA_DIR}/${MYSQL_HA_ROUTER_CONF_NAME}"
TARGET="${ADMIN_USER}@${SEED_HOST}:${MYSQL_HA_NODE_PORT}"

log() { printf '[router-init] %s\n' "$*"; }
err() { printf '[router-init] ERROR: %s\n' "$*" >&2; }

# --- cluster is really ready ------------------------------------------------
# The gating on mysql-cluster-init already guarantees a formed group, but the
# metadata schema is what the Router actually reads, so check for it instead of
# trusting a previous exit code across a compose recreate.
wait_for_metadata() {
  local attempt host found
  for attempt in $(seq 1 "$MYSQL_HA_INIT_RETRY_COUNT"); do
    found=""
    # shellcheck disable=SC2086 # NODES is a deliberate space-separated host list
    for host in $NODES; do
      if printf 'SHOW SCHEMAS LIKE "mysql_innodb_cluster_metadata";' |
        MYSQL_PWD="$MYSQL_ROOT_PASSWORD" timeout "$MYSQL_HA_PROBE_TIMEOUT_SECONDS" \
          "$MYSQL_BIN" --host="$host" --port="$MYSQL_HA_NODE_PORT" \
          --user="$ADMIN_USER" --protocol=TCP -N -B 2>/dev/null |
        grep -q mysql_innodb_cluster_metadata; then
        found="yes"
        break
      fi
    done
    if [[ -n "$found" ]]; then
      log "InnoDB Cluster metadata schema is present (attempt $attempt)"
      return 0
    fi
    log "metadata schema not visible yet (attempt $attempt/$MYSQL_HA_INIT_RETRY_COUNT)"
    sleep "$MYSQL_HA_INIT_RETRY_INTERVAL_SECONDS"
  done
  err "mysql_innodb_cluster_metadata was not found on any node."
  err "the cluster is not formed: inspect docker logs jl-mysql-cluster-init and"
  err "run db-cluster-status.sh before retrying."
  return 1
}

# --- data directory must be writable by *this* user ------------------------
preflight_data_dir() {
  if ! mkdir -p "$MYSQL_HA_ROUTER_DATA_DIR" 2>/dev/null; then
    err "cannot create $MYSQL_HA_ROUTER_DATA_DIR as uid=$(id -u)."
    err "fix: point MYSQL_HA_ROUTER_DATA_DIR at a path this image can write, or"
    err "mount the named volume over an existing (image-owned) directory."
    return 1
  fi
  if ! touch "$MYSQL_HA_ROUTER_DATA_DIR/.wtest" 2>/dev/null; then
    err "$MYSQL_HA_ROUTER_DATA_DIR exists but is not writable (uid=$(id -u), $(ls -ld "$MYSQL_HA_ROUTER_DATA_DIR" 2>/dev/null || echo 'stat failed'))."
    err "fix: run the init service as root, or chown the volume, or choose another"
    err "MYSQL_HA_ROUTER_DATA_DIR."
    return 1
  fi
  rm -f "$MYSQL_HA_ROUTER_DATA_DIR/.wtest"
}

# --- log level -------------------------------------------------------------
# MySQL Router has no --log-level flag (neither 8.0 nor 8.4): the level lives in
# [logger] level in the generated config, and the accepted values are fixed. An
# invalid value is not ignored - Router refuses to start with
# "Configuration error: Log level 'X' is not valid" - which is exactly how a
# hand-edited debug_3 in the named volume kept a "healthy" container from ever
# binding 6446/6447. So the value is externalised (MYSQL_HA_ROUTER_LOG_LEVEL) and
# validated here, before anything is written.
ROUTER_LOG_LEVELS="debug error fatal info note system warning"

validate_log_level() {
  local level
  # Router's own parser is case-insensitive (it accepts the bootstrap default
  # "INFO"), so compare lower-cased and pass the lower-cased value through.
  level="$(printf '%s' "${MYSQL_HA_ROUTER_LOG_LEVEL}" | tr '[:upper:]' '[:lower:]')"
  if [[ " $ROUTER_LOG_LEVELS " != *" $level "* ]]; then
    err "MYSQL_HA_ROUTER_LOG_LEVEL='$MYSQL_HA_ROUTER_LOG_LEVEL' is not valid for MySQL Router 8.4."
    err "valid values: $ROUTER_LOG_LEVELS (case-insensitive)."
    return 1
  fi
  MYSQL_HA_ROUTER_LOG_LEVEL="$level"
}

# --conf-set-option writes the level at bootstrap time; this also repairs an
# existing config on the skip path, where the old volume may still carry a level
# the current image rejects (the live 8.0->8.4 switch hit exactly that).
apply_log_level() {
  [[ -f "$CONF_PATH" ]] || return 0
  if grep -qE '^[[:space:]]*level[[:space:]]*=' "$CONF_PATH"; then
    sed -i -E "s/^([[:space:]]*level[[:space:]]*=).*/\1${MYSQL_HA_ROUTER_LOG_LEVEL}/" "$CONF_PATH"
  else
    printf '\n[logger]\nlevel=%s\n' "$MYSQL_HA_ROUTER_LOG_LEVEL" >>"$CONF_PATH"
  fi
}

bootstrap_args() {
  # Shared arguments for every password mode.
  # NB: the RW (6446) / RO (6447) classic routes and the X-protocol routes
  # (6448/6449, unused by the app) are what bootstrap generates by default; there
  # is no --conf-scope switch to ask for them (8.0.3x and 8.4.10 both reject it).
  # Same for --config-name: bootstrap always writes <directory>/mysqlrouter.conf,
  # which is exactly ${MYSQL_HA_ROUTER_CONF_NAME} here.
  printf '%s\0' \
    "--bootstrap=$TARGET" \
    "--directory=$MYSQL_HA_ROUTER_DATA_DIR" \
    "--name=$MYSQL_HA_ROUTER_NAME" \
    "--ssl-mode=$MYSQL_HA_ROUTER_SSL_MODE" \
    "--conf-set-option=logger.level=$MYSQL_HA_ROUTER_LOG_LEVEL"
  # --user is only accepted when the process is root; passing it otherwise makes
  # mysqlrouter abort, so it is conditional on the actual uid, not on a flag.
  if [[ "$(id -u)" == "0" ]]; then
    if id -u "$MYSQL_HA_ROUTER_USER" >/dev/null 2>&1; then
      printf '%s\0' "--user=$MYSQL_HA_ROUTER_USER"
    else
      log "user '$MYSQL_HA_ROUTER_USER' does not exist in this image; leaving Router privileges as-is"
    fi
  fi
  if [[ "${MYSQL_HA_ROUTER_FORCE_BOOTSTRAP:-false}" == "true" ]]; then
    printf '%s\0' "--force"
  fi
  if [[ -n "${MYSQL_HA_ROUTER_BOOTSTRAP_EXTRA_OPTS:-}" ]]; then
    local extra
    # shellcheck disable=SC2086 # deliberately a whitespace-separated opt list
    for extra in $MYSQL_HA_ROUTER_BOOTSTRAP_EXTRA_OPTS; do
      printf '%s\0' "$extra"
    done
  fi
}

# Password delivery for `mysqlrouter --bootstrap` is the one behaviour this lab
# could not verify offline (the Router prompts, and a non-TTY container may or may
# not accept stdin). Three modes, switched by env, so the first live run can pick
# the working one without editing this file:
#   stdin        printf the password into the prompt (default, nothing on argv)
#   option-file  write a 0600 option file and pass --defaults-extra-file
#   argv         --password=... (last resort: visible in `docker inspect`/ps)
run_bootstrap() {
  local -a args=()
  local a
  while IFS= read -r -d '' a; do args+=("$a"); done < <(bootstrap_args)

  case "$MYSQL_HA_ROUTER_PASSWORD_MODE" in
    stdin)
      printf '%s\n' "$MYSQL_ROOT_PASSWORD" |
        "$MYSQLROUTER_BIN" "${args[@]}"
      ;;
    option-file)
      local opts="$MYSQL_HA_ROUTER_DATA_DIR/.lab-router-bootstrap.cnf"
      umask 077
      {
        printf '[mysqlrouter]\n'
        printf 'password=%s\n' "$MYSQL_ROOT_PASSWORD"
      } >"$opts"
      if ! "$MYSQLROUTER_BIN" --defaults-extra-file="$opts" "${args[@]}"; then
        rm -f "$opts"
        return 1
      fi
      rm -f "$opts"
      ;;
    argv)
      log "WARN: MYSQL_HA_ROUTER_PASSWORD_MODE=argv puts the password on the command line (visible to ps / docker inspect). Lab-only."
      "$MYSQLROUTER_BIN" "${args[@]}" "--password=$MYSQL_ROOT_PASSWORD"
      ;;
    *)
      err "unknown MYSQL_HA_ROUTER_PASSWORD_MODE='$MYSQL_HA_ROUTER_PASSWORD_MODE' (stdin|option-file|argv)"
      return 2
      ;;
  esac
}

chown_data_dir() {
  if [[ "$(id -u)" == "0" ]] && id -u "$MYSQL_HA_ROUTER_USER" >/dev/null 2>&1; then
    chown -R "$MYSQL_HA_ROUTER_USER":"$MYSQL_HA_ROUTER_USER" "$MYSQL_HA_ROUTER_DATA_DIR" 2>/dev/null ||
      chown -R "$MYSQL_HA_ROUTER_USER" "$MYSQL_HA_ROUTER_DATA_DIR"
    log "data dir owned by $MYSQL_HA_ROUTER_USER (the Router drops to it at start)"
  fi
}

# Routing summary: only port numbers and backend addresses, never credentials.
print_summary() {
  log "generated $CONF_PATH (logger level=$MYSQL_HA_ROUTER_LOG_LEVEL)"
  grep -E '^(bind_ports|bind_address|routing_name|destinations|protocol|max_connections)\s*=' \
    "$CONF_PATH" 2>/dev/null | sed 's/^/    /' || log "  (could not read the config summary)"
  log "expected: ${MYSQL_HA_ROUTER_RW_PORT}=read/write, ${MYSQL_HA_ROUTER_RO_PORT}=read-only"
}

main() {
  validate_log_level
  if [[ -f "$CONF_PATH" && "${MYSQL_HA_ROUTER_FORCE_BOOTSTRAP:-false}" != "true" ]]; then
    log "config already present at $CONF_PATH - skipping bootstrap (set MYSQL_HA_ROUTER_FORCE_BOOTSTRAP=true to redo)"
    apply_log_level
    print_summary
    return 0
  fi
  wait_for_metadata
  preflight_data_dir
  log "bootstrapping Router against $TARGET (mode=$MYSQL_HA_ROUTER_PASSWORD_MODE, log-level=$MYSQL_HA_ROUTER_LOG_LEVEL)"
  if ! run_bootstrap; then
    err "mysqlrouter --bootstrap failed with mode=$MYSQL_HA_ROUTER_PASSWORD_MODE."
    err "retry with MYSQL_HA_ROUTER_PASSWORD_MODE=option-file, then =argv if the"
    err "image cannot be fed a prompt at all; the error text above says which."
    return 1
  fi
  chown_data_dir
  if [[ ! -f "$CONF_PATH" ]]; then
    err "bootstrap reported success but $CONF_PATH is missing - check the volume mount."
    return 1
  fi
  apply_log_level
  print_summary
  log "ROUTER BOOTSTRAP COMPLETE"
}

main "$@"
