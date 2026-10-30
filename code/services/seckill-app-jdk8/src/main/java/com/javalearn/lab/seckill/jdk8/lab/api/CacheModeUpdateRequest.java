package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * Body of {@code POST /lab/cache/mode}, e.g. {@code {"mode":"null-cache"}}.
 * The field is optional at the Jackson level so an empty body gets the same
 * {@code 40001} envelope as an unknown value.
 */
public class CacheModeUpdateRequest {

    private String mode;

    public CacheModeUpdateRequest() {
    }

    public CacheModeUpdateRequest(String mode) {
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
