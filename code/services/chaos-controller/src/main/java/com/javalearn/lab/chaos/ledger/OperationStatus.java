package com.javalearn.lab.chaos.ledger;

/**
 * Operation contract states (design §10.5). A request timeout is not a
 * failure: the operation becomes PENDING_CONFIRMATION until the controller can
 * prove what happened. After a restart, REQUESTED / PENDING_CONFIRMATION
 * operations become UNKNOWN (never assumed reverted).
 */
public enum OperationStatus {

    REQUESTED,
    APPLIED,
    RESTORED,
    PENDING_CONFIRMATION,
    FAILED,
    UNKNOWN;

    /** Operations that a restart cannot prove either way. */
    public boolean isUnconfirmed() {
        return this == REQUESTED || this == PENDING_CONFIRMATION;
    }
}
