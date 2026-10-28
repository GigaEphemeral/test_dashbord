-- Stage2 B1-02: operation contract, idempotency and audit fields.
--
-- `idempotency_key` is nullable: rows written without a key (every internal
-- operation and every caller that omits the header) stay unconstrained, because
-- MySQL ignores rows with a NULL part in a composite unique key. The unique key
-- covers key + action + target so the SAME key may still be used for a
-- different action/target. The window itself is NOT a schema constant: the
-- service treats the key as replayable while requested_at + the externalized
-- window has not elapsed, and releases (sets NULL) the expired key before it
-- allocates a new operation, so the key becomes reusable after the window.
--
-- before_summary / after_summary hold the audit snapshots returned by
-- GET /lab/operations/{id}. Payloads must never contain credentials (the
-- service scrubs them); operator is the configured audit identity (default
-- `local`) and is stored per operation so a later config change does not
-- rewrite history.

ALTER TABLE lab_operation
  ADD COLUMN idempotency_key VARCHAR(128) NULL,
  ADD COLUMN `operator` VARCHAR(64) NULL,
  ADD COLUMN before_summary JSON NULL,
  ADD COLUMN after_summary JSON NULL,
  ADD UNIQUE KEY uk_lab_operation_idempotency (idempotency_key, action, target_kind, target_name);
