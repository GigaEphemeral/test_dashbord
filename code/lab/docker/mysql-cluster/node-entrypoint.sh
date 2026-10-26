#!/usr/bin/env bash
# lab-mysql-cluster node entrypoint (compose profile db-ha).
#
# Runs before mysqld in every cluster node container: renders the node option
# file from conf/node.cnf.tpl plus the MYSQL_HA_* environment, sanity-checks it
# with `mysqld --validate-config`, then hands over to the MySQL image's own
# docker-entrypoint.sh so the standard initdb / MYSQL_DATABASE / MYSQL_USER /
# docker-entrypoint-initdb.d behaviour is preserved untouched.
#
# Compose invokes it as:
#   entrypoint: ["${MYSQL_HA_SHELL_BIN}", "${MYSQL_HA_ASSET_DIR}/node-entrypoint.sh"]
#   command:    ["mysqld", "--defaults-extra-file=${MYSQL_HA_NODE_CONF_FILE}"]
# so "$@" below is exactly `mysqld --defaults-extra-file=...` and the option file
# stays MySQL's required first argument.
set -euo pipefail

ASSET_DIR="${MYSQL_HA_ASSET_DIR:?MYSQL_HA_ASSET_DIR must be set by compose}"
# shellcheck disable=SC1090 # path is an env placeholder on purpose
source "$ASSET_DIR/lib/render-tpl.sh"

require_env \
  MYSQL_HA_NODE_TEMPLATE \
  MYSQL_HA_NODE_CONF_FILE \
  MYSQL_HA_SERVER_ID \
  MYSQL_HA_REPORT_HOST \
  MYSQL_HA_NODE_PORT \
  MYSQL_HA_GR_LOCAL_PORT \
  MYSQL_HA_CLUSTER_GROUP_NAME \
  MYSQL_HA_GR_IP_ALLOWLIST \
  MYSQL_HA_EXIT_STATE_ACTION \
  MYSQL_HA_MEMBER_WEIGHT \
  MYSQL_HA_AUTO_REJOIN_TRIES \
  MYSQL_HA_EXPEL_TIMEOUT_SECONDS \
  MYSQL_HA_REPLICA_PARALLEL_WORKERS \
  MYSQL_HA_PERFORMANCE_SCHEMA \
  MYSQL_HA_INNODB_BUFFER_POOL_SIZE \
  MYSQL_HA_MAX_CONNECTIONS \
  MYSQL_HA_MYSQL_1_HOST \
  MYSQL_HA_MYSQL_2_HOST \
  MYSQL_HA_MYSQL_3_HOST

# The group-communication address is <resolvable name>:<port>. Docker DNS answers
# the Compose service name on jl-net, so a container recreated with a new IP is
# still reachable by the group; an IP would silently break rejoin.
export MYSQL_HA_GR_LOCAL_ADDRESS="${MYSQL_HA_GR_LOCAL_ADDRESS:-${MYSQL_HA_REPORT_HOST}:${MYSQL_HA_GR_LOCAL_PORT}}"

# Seeds: one env key wins (explicit is better for demos), otherwise compose from
# the three host keys so the hostname list has exactly one source of truth.
if [[ -z "${MYSQL_HA_GROUP_SEEDS:-}" ]]; then
  export MYSQL_HA_GROUP_SEEDS="${MYSQL_HA_MYSQL_1_HOST}:${MYSQL_HA_GR_LOCAL_PORT},${MYSQL_HA_MYSQL_2_HOST}:${MYSQL_HA_GR_LOCAL_PORT},${MYSQL_HA_MYSQL_3_HOST}:${MYSQL_HA_GR_LOCAL_PORT}"
fi

render_template "$MYSQL_HA_NODE_TEMPLATE" "$MYSQL_HA_NODE_CONF_FILE"

# Advisory only: --validate-config (MySQL 8.0.16+) rejects unknown/duplicate
# options without touching the data directory. An 8.4-specific mistake such as a
# resurrected transaction_write_set_extraction is caught here and printed in
# plain words, but a failure does not block startup - mysqld itself is the
# authority and a quirk of the validator must not brick the node.
if command -v mysqld >/dev/null 2>&1; then
  if ! validate_output="$(mysqld --defaults-extra-file="$MYSQL_HA_NODE_CONF_FILE" --validate-config 2>&1)"; then
    printf 'WARN: mysqld --validate-config reported problems (startup continues):\n%s\n' \
      "$(printf '%s\n' "$validate_output" | head -n 20)" >&2
  fi
fi

printf 'node config ready: server_id=%s report_host=%s local_address=%s\n' \
  "$MYSQL_HA_SERVER_ID" "$MYSQL_HA_REPORT_HOST" "$MYSQL_HA_GR_LOCAL_ADDRESS"

# Never start mysqld directly: the image entrypoint owns initdb, the MYSQL_DATABASE
# / MYSQL_USER bootstrap and the init scripts, plus the root->mysql uid drop.
exec docker-entrypoint.sh "$@"
