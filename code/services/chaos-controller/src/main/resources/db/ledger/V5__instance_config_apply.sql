-- Stage2 B4-02: controlled rebuild outcome for lab_instance_config.
-- One row keeps the whole apply/rollback story: the stable period after a
-- successful OR failed rebuild (design §10.7 "失败后进入稳定期"), the image
-- identity actually running after the last apply (tag + image id, minimal
-- closure per confirmed decision #8) and the image identity that was running
-- before it (the rollback target, alongside previous_config).
--
-- Status values are owned by InstanceConfigStatus and all fit in VARCHAR(32):
-- DRAFT / PENDING_RESTART / APPLYING / APPLIED / FAILED / DEGRADED. DEGRADED is
-- written when an automatic restore fails, so the ledger never pretends the
-- instance is usable for a formal benchmark (design §10.7).
ALTER TABLE lab_instance_config
  MODIFY COLUMN status VARCHAR(32) NOT NULL,
  ADD COLUMN stable_until DATETIME(3) NULL AFTER last_error,
  ADD COLUMN applied_image_tag VARCHAR(128) NULL AFTER stable_until,
  ADD COLUMN applied_image_id VARCHAR(255) NULL AFTER applied_image_tag,
  ADD COLUMN last_apply_at DATETIME(3) NULL AFTER applied_image_id,
  ADD COLUMN previous_image_tag VARCHAR(128) NULL AFTER last_apply_at,
  ADD COLUMN previous_image_id VARCHAR(255) NULL AFTER previous_image_tag;
