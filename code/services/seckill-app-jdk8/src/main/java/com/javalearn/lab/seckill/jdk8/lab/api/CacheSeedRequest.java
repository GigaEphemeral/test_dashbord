package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * Optional {@code POST /lab/cache/seed} body. A missing body (or missing
 * {@code count}) keeps the configured {@code app.lab.cache.seed.product-count};
 * an explicit count is validated against that same maximum.
 */
public class CacheSeedRequest {

    private Long count;

    public CacheSeedRequest() {
    }

    public CacheSeedRequest(Long count) {
        this.count = count;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }
}
