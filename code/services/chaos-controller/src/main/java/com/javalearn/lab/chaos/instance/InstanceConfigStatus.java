package com.javalearn.lab.chaos.instance;

/**
 * Stage2 B4-01 stored status. B4-02 builds its apply/rollback state machine on
 * these names without a schema change:
 * {@code DRAFT} (saved only), {@code PENDING_RESTART} (queued for apply),
 * {@code APPLYING}, {@code APPLIED}, {@code FAILED}.
 */
public enum InstanceConfigStatus {
    DRAFT,
    PENDING_RESTART,
    APPLYING,
    APPLIED,
    FAILED
}
