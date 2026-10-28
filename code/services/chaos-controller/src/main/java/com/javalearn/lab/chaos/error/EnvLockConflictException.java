package com.javalearn.lab.chaos.error;

/**
 * B1-02: the single-writer environment lock is held by another run. 409/40902
 * (not the generic 40901) so the console can render the holder and the remedy
 * instead of a bare conflict. The message always names the holder run id and
 * how to release the lock.
 */
public class EnvLockConflictException extends RuntimeException {

    public EnvLockConflictException(String message) {
        super(message);
    }
}
