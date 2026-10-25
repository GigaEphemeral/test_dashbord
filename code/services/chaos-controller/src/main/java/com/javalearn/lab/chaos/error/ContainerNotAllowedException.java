package com.javalearn.lab.chaos.error;

public class ContainerNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ContainerNotAllowedException(String message) {
        super(message);
    }
}
