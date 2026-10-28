-- Stage2 B1-01: dedicated run ledger, physically separate from the `seckill`
-- business schema. Versioned by Flyway (classpath:db/ledger). All tables are
-- InnoDB + utf8mb4_0900_ai_ci; timestamps are UTC DATETIME(3) and JSON columns
-- use MySQL's native JSON type. Child rows cascade from lab_run so retention is
-- a single delete. Payloads must never contain credentials or full cache values.

CREATE TABLE lab_run (
  run_id VARCHAR(64) NOT NULL,
  scenario VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  source_commit VARCHAR(128) NULL,
  `operator` VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  started_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  PRIMARY KEY (run_id),
  KEY idx_lab_run_status_created (status, created_at),
  KEY idx_lab_run_scenario_created (scenario, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_run_step (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  step_key VARCHAR(128) NOT NULL,
  from_state VARCHAR(32) NULL,
  to_state VARCHAR(32) NULL,
  occurred_at DATETIME(3) NOT NULL,
  dwell_ms BIGINT NULL,
  detail JSON NULL,
  PRIMARY KEY (id),
  KEY idx_lab_run_step_run_time (run_id, occurred_at),
  CONSTRAINT fk_lab_run_step_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_run_env (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  container_name VARCHAR(128) NOT NULL,
  image_tag VARCHAR(255) NULL,
  image_id VARCHAR(255) NULL,
  java_version VARCHAR(32) NULL,
  jvm_args JSON NULL,
  gc JSON NULL,
  captured_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lab_run_env_run (run_id),
  CONSTRAINT fk_lab_run_env_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_run_param (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  group_name VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  saved_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lab_run_param_run_group (run_id, group_name),
  CONSTRAINT fk_lab_run_param_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_operation (
  operation_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NULL,
  target_kind VARCHAR(64) NOT NULL,
  target_name VARCHAR(128) NOT NULL,
  action VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  ttl_seconds INT NULL,
  requested_at DATETIME(3) NOT NULL,
  applied_at DATETIME(3) NULL,
  restored_at DATETIME(3) NULL,
  last_error VARCHAR(1024) NULL,
  PRIMARY KEY (operation_id),
  KEY idx_lab_operation_run (run_id),
  KEY idx_lab_operation_status_requested (status, requested_at),
  CONSTRAINT fk_lab_operation_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_fault_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  operation_id VARCHAR(64) NULL,
  source VARCHAR(32) NOT NULL,
  target VARCHAR(128) NOT NULL,
  `event` VARCHAR(64) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  detail JSON NULL,
  PRIMARY KEY (id),
  KEY idx_lab_fault_event_run_time (run_id, occurred_at),
  KEY idx_lab_fault_event_operation (operation_id),
  CONSTRAINT fk_lab_fault_event_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_metric_window (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  phase VARCHAR(32) NOT NULL,
  metric_key VARCHAR(128) NOT NULL,
  promql TEXT NOT NULL,
  window_start DATETIME(3) NOT NULL,
  window_end DATETIME(3) NOT NULL,
  stats JSON NULL,
  data_quality VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lab_metric_window_run_phase (run_id, phase),
  CONSTRAINT fk_lab_metric_window_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_run_check (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  check_key VARCHAR(128) NOT NULL,
  expected VARCHAR(1024) NULL,
  actual VARCHAR(1024) NULL,
  verdict VARCHAR(16) NOT NULL,
  checked_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lab_run_check_run (run_id),
  CONSTRAINT fk_lab_run_check_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_ledger_stash (
  id BIGINT NOT NULL AUTO_INCREMENT,
  kind VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  flushed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_lab_ledger_stash_flushed_created (flushed_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE lab_env_lock (
  lock_key VARCHAR(128) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  holder VARCHAR(64) NOT NULL,
  acquired_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (lock_key),
  KEY idx_lab_env_lock_expires (expires_at),
  CONSTRAINT fk_lab_env_lock_run FOREIGN KEY (run_id) REFERENCES lab_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
