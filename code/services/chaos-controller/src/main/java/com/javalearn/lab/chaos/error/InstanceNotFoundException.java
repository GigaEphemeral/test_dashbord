package com.javalearn.lab.chaos.error;

/** Stage2 B4-01: the instance name is not in {@code lab.instance.names}. */
public class InstanceNotFoundException extends RuntimeException {

    public InstanceNotFoundException(String message) {
        super(message);
    }
}
