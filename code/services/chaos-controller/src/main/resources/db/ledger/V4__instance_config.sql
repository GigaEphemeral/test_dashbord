-- Stage2 B4-01: per-instance JVM/GC/pool configuration ledger.
-- One row per configured app instance (app-1/2/3). `config` is the normalized
-- JSON produced by InstanceConfigValidator (never the raw request); saving a
-- draft keeps the previous normalized config and increments `revision`, so
-- B4-02 can roll back without a second table. Status values are owned by
-- InstanceConfigStatus: DRAFT / PENDING_RESTART / APPLYING / APPLIED / FAILED.
CREATE TABLE lab_instance_config (
  instance VARCHAR(64) NOT NULL,
  config JSON NOT NULL,
  previous_config JSON NULL,
  status VARCHAR(24) NOT NULL,
  revision INT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  applied_at DATETIME(3) NULL,
  last_error VARCHAR(1024) NULL,
  PRIMARY KEY (instance)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
