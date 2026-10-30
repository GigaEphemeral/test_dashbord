package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * Body of {@code POST /lab/toggles}, e.g. {@code {"key":"cache","value":false}}.
 * Both fields are optional at the Jackson level so the service can return the
 * same 400 envelope for an empty body, an unknown key and a missing value.
 */
public class ToggleUpdateRequest {

    private String key;
    private Boolean value;

    public ToggleUpdateRequest() {
    }

    public ToggleUpdateRequest(String key, Boolean value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Boolean getValue() {
        return value;
    }

    public void setValue(Boolean value) {
        this.value = value;
    }
}
