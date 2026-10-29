package com.javalearn.lab.seckill.lab.api;

import java.util.List;

/**
 * One page of {@code GET /lab/cache/keys}: the raw Redis SCAN cursor to pass
 * back as {@code cursor}, the keys of this page with their TTL, and the page
 * bound that produced them.
 *
 * <p>{@code cursor="0"} means the iteration is complete. {@code scanned} is the
 * number of keys Redis returned for this page (SCAN COUNT is only a hint, so a
 * page may be slightly larger or smaller than {@code limit}). TTL follows Redis
 * semantics: {@code -1} = no expiry, {@code -2} = the key disappeared between
 * SCAN and TTL lookup.</p>
 */
public record CacheKeysPageResponse(String cursor,
                                    List<Item> items,
                                    long scanned,
                                    int limit) {

    public record Item(String key, long ttlSeconds) {
    }
}
