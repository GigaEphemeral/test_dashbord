# Sentinel configuration template for the java-learn-lab HA topology.
#
# This file is NOT read by Redis directly. redis/sentinel-entrypoint.sh renders it
# into the container's writable tmpfs (REDIS_HA_SENTINEL_CONF, default
# /data/sentinel.conf) by replacing every @NAME@ placeholder with the environment
# variable of the same name, then starts `redis-server <conf> --sentinel`.
#
# Why a template instead of mounting this file as the live config: Sentinel
# rewrites its own configuration at runtime (myid, known-sentinel, known-replica,
# epoch/failsafe lines, and the master address after a failover). A read-only
# bind mount would make those rewrites fail, so the template stays read-only on
# the host and the runtime copy lives on tmpfs, rebuilt from this template on
# every container start.
#
# Every placeholder must exist in the environment (compose.ha.yml wires them from
# code/lab/docker/.env); the render step fails fast instead of writing a broken
# config when one is missing. Placeholders look like an at-sign, the variable name,
# then another at-sign.
port @REDIS_HA_SENTINEL_PORT@
bind 0.0.0.0
protected-mode @REDIS_HA_SENTINEL_PROTECTED_MODE@
daemonize no
dir @REDIS_HA_SENTINEL_DIR@
logfile ""

# Quorum is the number of Sentinels that must agree the master is down before a
# failover starts (3 Sentinels + quorum 2 tolerates one Sentinel being wrong).
# @REDIS_HA_MASTER_HOST@ must be an IP address, not a container/service name: with
# resolve-hostnames off Redis refuses a hostname here ("Can't resolve instance
# hostname", FATAL CONFIG ERROR), and with it on a Sentinel re-resolves the monitored
# address on every check, so `docker stop <master>` removes the name from Docker's
# embedded DNS and the Sentinel enters #tilt mode instead of failing over. The lab
# therefore pins a stable IP per data node (see the jl-sentinel-net subnet in
# compose.ha.yml and the REDIS_HA_REDIS_*_IP keys in .env.example).
sentinel monitor @REDIS_HA_MASTER_NAME@ @REDIS_HA_MASTER_HOST@ @REDIS_HA_MASTER_PORT@ @REDIS_HA_QUORUM@
sentinel down-after-milliseconds @REDIS_HA_MASTER_NAME@ @REDIS_HA_DOWN_AFTER_MS@
sentinel parallel-syncs @REDIS_HA_MASTER_NAME@ @REDIS_HA_PARALLEL_SYNC@
sentinel failover-timeout @REDIS_HA_MASTER_NAME@ @REDIS_HA_FAILOVER_TIMEOUT_MS@

# Password used to authenticate against the monitored data nodes. It is the same
# REDIS_HA_PASSWORD the data nodes start with as requirepass/masterauth, so the
# Sentinels can also issue REPLICAOF during a failover.
sentinel auth-pass @REDIS_HA_MASTER_NAME@ @REDIS_HA_PASSWORD@

# Both flags stay off in this lab: identities are pinned IPs, so nothing has to be
# resolved and announcing hostnames would only re-create the tilt-mode failure above.
sentinel resolve-hostnames @REDIS_HA_RESOLVE_HOSTNAMES@
sentinel announce-hostnames @REDIS_HA_ANNOUNCE_HOSTNAMES@
