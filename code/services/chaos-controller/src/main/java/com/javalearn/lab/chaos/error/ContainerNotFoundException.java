package com.javalearn.lab.chaos.error;

public class ContainerNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ContainerNotFoundException(String message) {
        super(message);
    }
}
