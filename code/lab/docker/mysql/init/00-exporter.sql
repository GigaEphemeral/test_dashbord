-- Lab-only account used by jl-mysqld-exporter.
-- The password is intentionally the same as the username; this database is local-only.
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
FLUSH PRIVILEGES;
