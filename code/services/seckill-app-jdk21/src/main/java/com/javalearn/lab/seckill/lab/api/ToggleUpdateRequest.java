package com.javalearn.lab.seckill.lab.api;

/**
 * Body of {@code POST /lab/toggles}, e.g. {@code {"key":"cache","value":false}}.
 * Both fields are optional at the Jackson level so the service can return the
 * same 400 envelope for an empty body, an unknown key and a missing value.
 */
public record ToggleUpdateRequest(String key, Boolean value) {
}
