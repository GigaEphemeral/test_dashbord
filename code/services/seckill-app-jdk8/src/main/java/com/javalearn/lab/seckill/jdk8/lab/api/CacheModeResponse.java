package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * {@code GET/POST /lab/cache/mode} payload: the defense mode this instance is
 * currently running.
 */
public final class CacheModeResponse {

    private final String mode;

    public CacheModeResponse(String mode) {
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    public String mode() {
        return mode;
    }
}
