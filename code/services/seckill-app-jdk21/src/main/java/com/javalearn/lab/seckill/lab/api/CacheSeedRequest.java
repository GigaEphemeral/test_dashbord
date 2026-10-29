package com.javalearn.lab.seckill.lab.api;

/**
 * Optional {@code POST /lab/cache/seed} body. A missing body (or missing
 * {@code count}) keeps the configured {@code app.lab.cache.seed.product-count};
 * an explicit count is validated against that same maximum.
 */
public record CacheSeedRequest(Long count) {
}
