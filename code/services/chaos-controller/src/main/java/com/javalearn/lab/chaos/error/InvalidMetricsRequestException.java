package com.javalearn.lab.chaos.error;

/** Invalid metric window / key / phase input: 400, never a silent default. */
public class InvalidMetricsRequestException extends RuntimeException {

    public InvalidMetricsRequestException(String message) {
        super(message);
    }
}
