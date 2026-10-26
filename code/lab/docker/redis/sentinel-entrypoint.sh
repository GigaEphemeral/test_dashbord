#!/bin/sh
# Renders redis/sentinel.conf.tpl into a writable runtime copy and starts
# `redis-server <conf> --sentinel` (compose profile "ha").
#
# Sentinel rewrites its own configuration file while it runs (myid, known-replica,
# known-sentinel, epochs, and the promoted master after a failover), so the file it
# is given must be a writable copy, never the read-only bind-mounted template.
#
# Placeholders are filled from the environment: the @NAME@ list is discovered in the
# template itself, so compose.ha.yml carries no numbers and adding a placeholder to
# the template is enough to make it configurable. A missing/empty variable fails
# fast rather than writing a half-empty config.
#
# Executed as `sh <path>` by compose, so the host file's executable bit does not
# matter (Docker Desktop bind-mounts Windows files).
set -eu

TEMPLATE="${REDIS_HA_SENTINEL_TEMPLATE:-/etc/redis/sentinel.conf.tpl}"
CONF="${REDIS_HA_SENTINEL_CONF:-/data/sentinel.conf}"
# `dir` in the template always points at the directory holding the runtime copy,
# so Sentinel's rewrites and any bookkeeping stay on the same writable filesystem.
REDIS_HA_SENTINEL_DIR="$(dirname "$CONF")"
export REDIS_HA_SENTINEL_DIR

if [ ! -f "$TEMPLATE" ]; then
  echo "ERROR: sentinel config template not found: $TEMPLATE" >&2
  exit 1
fi
mkdir -p "$REDIS_HA_SENTINEL_DIR"

TMP="$CONF.render.$$"
# Copy through `tr -d '\r'`: the lab lives on Windows, and a clone with
# core.autocrlf=true would hand us the template as CRLF. A trailing \r inside the
# rendered config would silently change values (port, quorum, the auth-pass password)
# instead of failing loudly, so line endings are normalised before any substitution.
tr -d '\r' < "$TEMPLATE" > "$TMP"

# Discover the placeholders from the template itself (comment lines excluded, so the
# documentation can mention the syntax without becoming a required variable).
for key in $(sed '/^[[:space:]]*#/d' "$TMP" | grep -o '@[A-Za-z0-9_]*@' | sed 's/@//g' | sort -u); do
  value="$(printenv "$key" || true)"
  if [ -z "$value" ]; then
    echo "ERROR: $key is empty or unset; $TEMPLATE needs it (see code/lab/docker/.env.example)." >&2
    rm -f "$TMP"
    exit 1
  fi
  # Quote the replacement so a value containing \, & or | cannot corrupt the sed
  # expression (the password is user-supplied).
  escaped="$(printf '%s' "$value" | sed 's/[\\&|]/\\&/g')"
  sed "s|@$key@|$escaped|g" "$TMP" > "$TMP.next"
  mv "$TMP.next" "$TMP"
done

mv "$TMP" "$CONF"
exec redis-server "$CONF" --sentinel
