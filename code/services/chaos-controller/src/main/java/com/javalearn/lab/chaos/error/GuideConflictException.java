package com.javalearn.lab.chaos.error;

/** Guide command is invalid in the current state (HTTP 409). */
public class GuideConflictException extends ChaosConflictException {

    private static final long serialVersionUID = 1L;

    public GuideConflictException(String message) {
        super(message);
    }
}
