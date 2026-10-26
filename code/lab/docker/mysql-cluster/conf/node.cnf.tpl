# lab-mysql-cluster node option file (TEMPLATE)
#
# Rendered at container start by ../node-entrypoint.sh into
# ${MYSQL_HA_NODE_CONF_FILE}, which mysqld then reads through
# --defaults-extra-file (see compose.mysql.yml). Every double-at-sign NAME token
# below is replaced by the environment variable of the same name; the renderer
# fails fast if a token has no value, so nothing operational is hardcoded in this
# file and .env stays the single source of truth (doc/design/工程规约.md §1).
#
# WRITING RULE FOR THIS FILE: never spell a literal example token in a comment.
# lib/render-tpl.sh scans the WHOLE file, comments included, so an example token
# in prose becomes a required environment variable (this cost a real crash-loop:
# "requires environment variable NAME (no default)"). Use words like
# "double-at-sign NAME" in prose instead.
#
# Why an option file instead of mysqld command-line flags: the per-node identity
# (server_id / report_host / group_replication_local_address) differs on every
# node while the ~25 Group Replication settings are identical, and Compose can
# only interpolate env into a scalar - a file keeps one anchor for all three
# nodes instead of three duplicated 25-line `command:` blocks.
#
# MySQL 8.4 SPECIFICS (verified against the 8.4 reference manual, and the reason
# a 5.7/8.0-era recipe must not be copied here):
#   - transaction_write_set_extraction was REMOVED in MySQL 8.3.0.
#   - binlog_transaction_dependency_tracking was REMOVED in MySQL 8.4.0
#     (deprecated in 8.2.0). The server always extracts write sets now.
#   Setting either one makes mysqld refuse to start with "unknown variable",
#     so neither may ever be added back to this file.
#   - The slave_* names are gone; replica_* spellings are used below.
#   - binlog_checksum keeps its CRC32 default: Group Replication in 8.x supports
#     checksummed events, so it is deliberately not disabled here.

[mysqld]

# --- per-node identity (unique in the group) --------------------------------
# server_id doubles as the GTID source id, so three nodes with the same id would
# produce colliding GTIDs and the group would refuse to form.
server-id                              = @@MYSQL_HA_SERVER_ID@@
# report_host is what the instance announces to the group and to the Shell /
# Router metadata. It MUST be a name every peer can resolve - inside Compose
# that is the service/container name (mysql-1), never 127.0.0.1 and never the
# container's short hostname, which Docker DNS does not answer for.
report_host                            = @@MYSQL_HA_REPORT_HOST@@
report_port                            = @@MYSQL_HA_NODE_PORT@@

# --- replication framework required by Group Replication --------------------
gtid_mode                              = ON
enforce_gtid_consistency               = ON
log_replica_updates                    = ON
binlog_format                          = ROW
disabled_storage_engines               = MyISAM,BLACKHOLE,FEDERATED,ARCHIVE,MEMORY
# Single-primary applies transactions in parallel through the replica applier,
# so the applier needs more than one worker and must preserve commit order.
replica_parallel_workers               = @@MYSQL_HA_REPLICA_PARALLEL_WORKERS@@
replica_preserve_commit_order           = ON

# --- Group Replication plugin ----------------------------------------------
# plugin-load-add loads the plugin at startup; group_replication_start_on_boot
# stays OFF so the member only joins when the AdminAPI (create-cluster.js)
# bootstraps the group - otherwise a plain `compose up` would try to form a
# group before the seeds are reachable.
plugin-load-add                        = group_replication.so

# --- Group Replication ------------------------------------------------------
# IMPORTANT (live-bootstrap finding, 2026-10-27): every group_replication_* option
# below is written with the `loose-` prefix. Without it mysqld aborts with
# "unknown variable 'group_replication_...'" during the image's first `--initialize`
# pass, because the GR plugin cannot be loaded while initializing (mysqld prints
# "Ignoring --plugin-load[_add] list as the server is running with --initialize"),
# so the plugin's variables are not yet registered and are treated as unknown.
# `loose-` = "apply it if the variable exists, ignore it (warn only) if it does
# not": harmless during initdb, and at normal runtime plugin-load-add has already
# registered the variables so each value is applied exactly as before.
loose-group_replication_group_name           = @@MYSQL_HA_CLUSTER_GROUP_NAME@@
loose-group_replication_start_on_boot        = OFF
loose-group_replication_bootstrap_group      = OFF
loose-group_replication_local_address        = @@MYSQL_HA_GR_LOCAL_ADDRESS@@
loose-group_replication_group_seeds          = @@MYSQL_HA_GROUP_SEEDS@@
# Allowlist for the group-communication port. In a shared Docker bridge network
# member IPs change on every recreate, so the default AUTOMATERIALIZE entries go
# stale and rejoin is rejected; 0.0.0.0/0 keeps the lab deterministic. The
# blast radius is one local-only bridge network - tighten it for any real
# deployment.
loose-group_replication_ip_allowlist         = @@MYSQL_HA_GR_IP_ALLOWLIST@@
# READ_ONLY instead of the default ABORT_SERVER: a partitioned minority demotes
# itself to super_read_only and comes back on its own, which is exactly the
# behaviour the failover demo needs to show (and it never restart-loops a node).
loose-group_replication_exit_state_action    = @@MYSQL_HA_EXIT_STATE_ACTION@@
loose-group_replication_single_primary_mode  = ON
loose-group_replication_member_weight        = @@MYSQL_HA_MEMBER_WEIGHT@@
loose-group_replication_auto_rejoin_tries    = @@MYSQL_HA_AUTO_REJOIN_TRIES@@
loose-group_replication_member_expel_timeout = @@MYSQL_HA_EXPEL_TIMEOUT_SECONDS@@

# --- lab sizing -------------------------------------------------------------
# performance_schema must stay ON: performance_schema.replication_group_members
# (the cluster membership view that db-cluster-status.sh, the exporters and the
# chaos-controller probe all read) lives there.
performance_schema                     = @@MYSQL_HA_PERFORMANCE_SCHEMA@@
innodb_buffer_pool_size                = @@MYSQL_HA_INNODB_BUFFER_POOL_SIZE@@
max_connections                        = @@MYSQL_HA_MAX_CONNECTIONS@@
character-set-server                   = utf8mb4
collation-server                       = utf8mb4_0900_ai_ci
