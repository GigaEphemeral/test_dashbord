package com.javalearn.lab.seckill.jdk8.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;

/**
 * Startup defaults for the runtime toggles.
 *
 * <p>All six values come from {@code app.toggles.*} in {@code application.yml}
 * (environment-overridable). There are deliberately no Java defaults: a missing
 * key fails startup instead of hiding a different protection level.</p>
 */
@ConfigurationProperties(prefix = "app.toggles")
public class LabToggleProperties {

    private Boolean lock;
    private Boolean idempotency;
    private Boolean mq;
    private Boolean ratelimit;
    private Boolean circuitbreaker;
    private Boolean cache;

    @PostConstruct
    void validate() {
        require(lock, "app.toggles.lock");
        require(idempotency, "app.toggles.idempotency");
        require(mq, "app.toggles.mq");
        require(ratelimit, "app.toggles.ratelimit");
        require(circuitbreaker, "app.toggles.circuitbreaker");
        require(cache, "app.toggles.cache");
    }

    private static void require(Boolean value, String key) {
        if (value == null) {
            throw new IllegalStateException("Missing required configuration: " + key);
        }
    }

    public Boolean getLock() {
        return lock;
    }

    public void setLock(Boolean lock) {
        this.lock = lock;
    }

    public Boolean getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Boolean idempotency) {
        this.idempotency = idempotency;
    }

    public Boolean getMq() {
        return mq;
    }

    public void setMq(Boolean mq) {
        this.mq = mq;
    }

    public Boolean getRatelimit() {
        return ratelimit;
    }

    public void setRatelimit(Boolean ratelimit) {
        this.ratelimit = ratelimit;
    }

    public Boolean getCircuitbreaker() {
        return circuitbreaker;
    }

    public void setCircuitbreaker(Boolean circuitbreaker) {
        this.circuitbreaker = circuitbreaker;
    }

    public Boolean getCache() {
        return cache;
    }

    public void setCache(Boolean cache) {
        this.cache = cache;
    }
}
