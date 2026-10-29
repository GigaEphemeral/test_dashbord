-- Stage2 B3-00a: experiment draft fields on lab_run.
--   name      operator-facing test name (run_id stays the immutable key)
--   nodes     enabled Java service names, comma separated ("app-1,app-2");
--             NULL keeps the pre-B3 behaviour (all configured targets)
--   archived  hidden from the default list; retention never prunes archived runs
ALTER TABLE lab_run
  ADD COLUMN name VARCHAR(128) NULL AFTER scenario,
  ADD COLUMN nodes VARCHAR(255) NULL AFTER name,
  ADD COLUMN archived TINYINT(1) NOT NULL DEFAULT 0 AFTER ended_at,
  ADD KEY idx_lab_run_archived_created (archived, created_at);
