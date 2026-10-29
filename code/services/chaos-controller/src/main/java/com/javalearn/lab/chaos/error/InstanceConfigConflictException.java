package com.javalearn.lab.chaos.error;

/**
 * Stage2 B4-01: startup parameters (JVM/GC/log) cannot be changed while a run
 * is RUNNING. The message names the blocking run so the console can point at
 * its stop action.
 */
public class InstanceConfigConflictException extends RuntimeException {

    public InstanceConfigConflictException(String message) {
        super(message);
    }
}
