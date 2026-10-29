package com.javalearn.lab.seckill.lab.api;

/**
 * {@code POST /lab/cache/reset} result. {@code includeBusiness=true} additionally
 * truncates {@code order_order} and restores the deterministic inventory
 * baseline, so the caller must have confirmed that destructive part first.
 */
public record CacheExperimentResetResponse(long deletedKeys, boolean includeBusiness) {
}
