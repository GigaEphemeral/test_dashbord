package com.javalearn.lab.chaos.error;

public class DockerUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DockerUnavailableException(String message) {
        super(message);
    }

    public DockerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
