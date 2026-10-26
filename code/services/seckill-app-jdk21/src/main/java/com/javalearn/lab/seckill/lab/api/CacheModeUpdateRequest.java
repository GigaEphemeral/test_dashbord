package com.javalearn.lab.seckill.lab.api;

/**
 * Body of {@code POST /lab/cache/mode}, e.g. {@code {"mode":"null-cache"}}.
 * The field is optional at the Jackson level so an empty body gets the same
 * {@code 40001} envelope as an unknown value.
 */
public record CacheModeUpdateRequest(String mode) {
}
