package com.javalearn.lab.chaos.error;

/** Requested scenario id does not exist (HTTP 404). */
public class ScenarioNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScenarioNotFoundException(String message) {
        super(message);
    }
}
