#!/bin/sh
# Redis data-node launcher for the Sentinel topology (compose profile "ha").
#
# Every runtime value is read from the environment (see .env.example and
# compose.ha.yml); only the Redis flag names live here. The container is started
# as `redis-server ... --replicaof <host> <port>` for a replica, exactly like the
# manual command a student would type, so `docker exec jl-redis-2 redis-cli info
# replication` shows a real replica relationship.
#
# This file is bind-mounted read-only and executed as `sh <path>` so the host
# file's executable bit is irrelevant (Docker Desktop mounts Windows files).
set -eu

NODE_PORT="${REDIS_HA_NODE_PORT:-6379}"

set -- redis-server \
  --port "$NODE_PORT" \
  --bind 0.0.0.0 \
  --protected-mode "${REDIS_HA_PROTECTED_MODE:-no}" \
  --maxmemory "${REDIS_HA_MAXMEMORY:-128mb}" \
  --maxmemory-policy "${REDIS_HA_MAXMEMORY_POLICY:-allkeys-lru}" \
  --appendonly "${REDIS_HA_APPENDONLY:-no}" \
  --timeout "${REDIS_HA_IDLE_TIMEOUT_SECONDS:-0}" \
  --tcp-keepalive "${REDIS_HA_TCP_KEEPALIVE_SECONDS:-300}"

# Identity is opt-in. By default each node announces its real Docker IP, which is
# what Sentinel must use to fail over: when a container is stopped its network alias
# disappears from Docker's embedded DNS, and a Sentinel running with
# `resolve-hostnames yes` then logs "Failed to resolve hostname", enters #tilt mode
# and refuses to promote anybody (measured during the M3-1a drill, see step-24a).
# Set REDIS_HA_ANNOUNCE_IP (plus REDIS_HA_ANNOUNCE_PORT) only when the node's name is
# guaranteed to survive an outage, e.g. a stable VIP/alias managed outside Compose.
if [ -n "${REDIS_HA_ANNOUNCE_IP:-}" ]; then
  set -- "$@" --replica-announce-ip "$REDIS_HA_ANNOUNCE_IP" \
              --replica-announce-port "${REDIS_HA_ANNOUNCE_PORT:-$NODE_PORT}"
fi

# Empty REDIS_HA_REPLICAOF_HOST (the master) keeps the node writable as master.
if [ -n "${REDIS_HA_REPLICAOF_HOST:-}" ]; then
  set -- "$@" --replicaof "$REDIS_HA_REPLICAOF_HOST" "${REDIS_HA_REPLICAOF_PORT:-6379}"
fi

# One lab password for the whole HA group. `requirepass` protects the node,
# `masterauth` is what lets it follow whichever node Sentinel promotes: without it
# the demoted old master cannot authenticate against the new master and never
# rejoins the replica set. Both are the same value on purpose.
if [ "${REDIS_HA_REQUIRE_AUTH:-true}" = "true" ]; then
  if [ -z "${REDIS_HA_PASSWORD:-}" ]; then
    echo "ERROR: REDIS_HA_REQUIRE_AUTH=true but REDIS_HA_PASSWORD is empty." >&2
    exit 1
  fi
  set -- "$@" --requirepass "$REDIS_HA_PASSWORD" --masterauth "$REDIS_HA_PASSWORD"
fi

exec "$@"
