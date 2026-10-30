package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

/**
 * What asked for a preheat run. The key is the {@code trigger} metric label and
 * the value shown by {@code GET /lab/cache/preheat}.
 */
public enum PreheatTrigger {

    /** Asynchronous run shortly after ApplicationReadyEvent. */
    STARTUP("startup"),
    /** Incremental reheat every {@code periodic-interval-seconds}. */
    PERIODIC("periodic"),
    /** Explicit {@code POST /lab/cache/preheat}. */
    MANUAL("manual");

    private final String key;

    PreheatTrigger(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
