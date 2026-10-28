package com.javalearn.lab.chaos.ledger;

/**
 * The work behind an operation. Runs on the dedicated operation executor, so it
 * must be self-contained (no request-scoped state) and is allowed to block.
 */
@FunctionalInterface
public interface OperationWork {

    /**
     * Performs the side effect and returns what the audit should record.
     *
     * @throws RuntimeException a definite failure: the operation is settled as
     *                          FAILED with the scrubbed message and the caller
     *                          (if still waiting) receives the same exception
     */
    OperationOutcome run();
}
