package com.javalearn.lab.chaos.ledger;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The operation currently executing on this thread.
 *
 * <p>{@link OperationService} inserts the operation row before the worker runs,
 * so a start gate that runs inside the worker sees its own row. The pre-check
 * must not block on itself - and status alone cannot tell "my own in-flight
 * operation" from "a stale sibling" once the sync budget has flipped the row to
 * PENDING_CONFIRMATION. This context carries the exact id instead.</p>
 *
 * <p>Only the operation executor sets it. Callers that are not running inside
 * an operation see {@link Optional#empty()}.</p>
 */
public final class OperationContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private OperationContext() {
    }

    public static Optional<String> currentOperationId() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Runs {@code work} with {@code operationId} bound to this thread and
     * restores the previous binding (the executor may nest calls in tests).
     */
    public static <T> T withOperation(String operationId, Supplier<T> work) {
        String previous = CURRENT.get();
        CURRENT.set(operationId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
