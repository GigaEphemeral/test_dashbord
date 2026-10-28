package com.javalearn.lab.chaos.error;

/** The requested operation id does not exist in {@code lab_operation}. */
public class OperationNotFoundException extends RuntimeException {

    public OperationNotFoundException(String message) {
        super(message);
    }
}
