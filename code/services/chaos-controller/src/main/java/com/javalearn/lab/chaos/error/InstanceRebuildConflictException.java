package com.javalearn.lab.chaos.error;

/**
 * Stage2 B4-02: an apply/rollback was refused before any Docker mutation.
 * Typical causes: the instance has no stored configuration, a rollback has no
 * previous configuration, or another rebuild is already applying this instance.
 * Distinct from {@link InstanceConfigConflictException} (40906, a RUNNING run
 * locks startup fields) so the console can point at the right remedy.
 */
public class InstanceRebuildConflictException extends RuntimeException {

    public InstanceRebuildConflictException(String message) {
        super(message);
    }
}
