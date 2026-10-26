-- lab-mysql-cluster accounts (TEMPLATE).
--
-- Same shape and lab-only credential policy as code/lab/docker/mysql/init/
-- 00-exporter.sql, which documents the single-node exporter account. Every
-- double-at-sign token is replaced from the environment by
-- cluster-init-entrypoint.sh (lib/render-tpl.sh) before this is piped to the mysql
-- client, so no password is committed and nothing has to be re-typed if the .env
-- values change.
--
-- WRITING RULE FOR THIS FILE: never spell a literal example token in a comment -
-- render-tpl.sh scans comments too, so a prose example becomes a required env var.
--
-- WHY NOT docker-entrypoint-initdb.d: the init scripts of the MySQL image run on
-- EVERY node during its own initdb, each writing into its own GTID stream with a
-- different server_uuid. Those are errant transactions: Group Replication then
-- rejects the joining instance and the only cure is a full clone. Applying the
-- statements once, on the elected primary, after dba.createCluster() succeeded,
-- lets the DDL replicate normally and keeps one GTID source of truth.

-- --- mysqld-exporter (one container per node, same account name as dev) -----
-- The exporter account name/password also appear in compose.mysql.yml's exporter
-- command line, so changing MYSQL_HA_EXPORTER_USER / MYSQLD_EXPORTER_PASSWORD in
-- .env is enough - no second file to edit (unlike the dev stack, where the
-- password is fixed in mysql/init/00-exporter.sql).
CREATE USER IF NOT EXISTS '@@MYSQL_HA_EXPORTER_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@' IDENTIFIED BY '@@MYSQL_HA_EXPORTER_PASSWORD@@';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO '@@MYSQL_HA_EXPORTER_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@';

-- --- cluster monitor (chaos-controller probe + db-cluster-status.sh) --------
-- Read-only by construction: no DML, no DDL, no REPLICATION SLAVE (it never reads
-- the binary log, only the membership views). Task M3-2b points the ~10s
-- mysqlshell/SQL poller at this account, so the credentials never have to be
-- invented there.
CREATE USER IF NOT EXISTS '@@MYSQL_HA_MONITOR_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@' IDENTIFIED BY '@@MYSQL_HA_MONITOR_PASSWORD@@';
GRANT USAGE ON *.* TO '@@MYSQL_HA_MONITOR_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@';
GRANT PROCESS, REPLICATION CLIENT ON *.* TO '@@MYSQL_HA_MONITOR_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@';
GRANT SELECT ON `performance_schema`.* TO '@@MYSQL_HA_MONITOR_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@';
-- No GRANT on `information_schema`.*: MySQL forbids granting on it
-- (ERROR 1044 "Access denied ... to database 'information_schema'"). Its tables are
-- globally readable to every authenticated session anyway, so lab_monitor can
-- SELECT from information_schema without any grant.
-- InnoDB Cluster metadata: needed by mysqlsh dba.getCluster()/status(), and by any
-- tool that must resolve the cluster from the metadata schema instead of argv.
GRANT SELECT ON `mysql_innodb_cluster_metadata`.* TO '@@MYSQL_HA_MONITOR_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@';

-- --- application account ---------------------------------------------------
-- MYSQL_USER / MYSQL_PASSWORD exist on every node from the image's own initdb; the
-- statements below only make the lab survive a re-bootstrap where the volume was
-- purged, and keep the password in sync with .env (IF NOT EXISTS never overwrites
-- an existing account's password - ALTER USER would, and is deliberately not used
-- so a running cluster can never be locked out mid-demo by this script).
CREATE USER IF NOT EXISTS '@@MYSQL_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@' IDENTIFIED BY '@@MYSQL_PASSWORD@@';
GRANT ALL PRIVILEGES ON `@@MYSQL_DATABASE@@`.* TO '@@MYSQL_USER@@'@'@@MYSQL_HA_ACCOUNT_HOST_PATTERN@@';

FLUSH PRIVILEGES;
