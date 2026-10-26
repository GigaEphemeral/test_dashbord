package com.javalearn.lab.chaos.error;

/** Scenario JSON is invalid (HTTP 400). */
public class InvalidScenarioException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidScenarioException(String message) {
        super(message);
    }
}
