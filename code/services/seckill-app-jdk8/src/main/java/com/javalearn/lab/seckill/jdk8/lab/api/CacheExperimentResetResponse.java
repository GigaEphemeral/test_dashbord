package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * {@code POST /lab/cache/reset} result. {@code includeBusiness=true} additionally
 * truncates {@code order_order} and restores the deterministic inventory
 * baseline, so the caller must have confirmed that destructive part first.
 */
public final class CacheExperimentResetResponse {

    private final long deletedKeys;
    private final boolean includeBusiness;

    public CacheExperimentResetResponse(long deletedKeys, boolean includeBusiness) {
        this.deletedKeys = deletedKeys;
        this.includeBusiness = includeBusiness;
    }

    public long getDeletedKeys() {
        return deletedKeys;
    }

    public boolean isIncludeBusiness() {
        return includeBusiness;
    }

    public long deletedKeys() {
        return deletedKeys;
    }

    public boolean includeBusiness() {
        return includeBusiness;
    }
}
