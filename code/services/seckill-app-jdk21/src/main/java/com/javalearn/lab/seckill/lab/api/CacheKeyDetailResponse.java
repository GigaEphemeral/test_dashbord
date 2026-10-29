package com.javalearn.lab.seckill.lab.api;

/**
 * {@code POST /lab/cache/key}: the L2 value (byte-capped), its TTL and whether
 * the same product id is present in the instance-local L1.
 *
 * <p>The L1 flag is a non-counting peek: inspecting a key must not change the
 * hit/miss counters the experiment reads. A key absent from both layers is a
 * 404 instead of this payload.</p>
 */
public record CacheKeyDetailResponse(String key,
                                     long productId,
                                     boolean present,
                                     boolean l2Present,
                                     boolean l1Present,
                                     Long ttlSeconds,
                                     String value,
                                     boolean truncated,
                                     int valueBytes,
                                     int valueMaxBytes) {
}
