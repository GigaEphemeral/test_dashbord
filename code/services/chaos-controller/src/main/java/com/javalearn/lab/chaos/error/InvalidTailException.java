package com.javalearn.lab.chaos.error;

public class InvalidTailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidTailException(String message) {
        super(message);
    }
}
