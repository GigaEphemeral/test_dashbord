package com.javalearn.lab.chaos.instance;

/**
 * Stage2 B4 stored status. B4-02 drives the apply/rollback state machine on
 * these names without a further schema change:
 * {@code DRAFT} (saved only), {@code PENDING_RESTART} (queued for apply),
 * {@code APPLYING} (rebuild in flight), {@code APPLIED} (the stored config is
 * what the running container reports), {@code FAILED} (the last rebuild failed
 * and the previous container was restored) and {@code DEGRADED} (the rebuild
 * failed AND the automatic restore failed, so the instance needs an operator;
 * design §10.7 forbids a formal benchmark in this state).
 */
public enum InstanceConfigStatus {
    DRAFT,
    PENDING_RESTART,
    APPLYING,
    APPLIED,
    ROLLED_BACK,
    FAILED,
    DEGRADED
}
