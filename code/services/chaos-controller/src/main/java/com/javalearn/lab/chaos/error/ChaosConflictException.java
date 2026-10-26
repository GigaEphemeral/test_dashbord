package com.javalearn.lab.chaos.error;

/** The target already has an active action that will revert later (HTTP 409). */
public class ChaosConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChaosConflictException(String message) {
        super(message);
    }
}
