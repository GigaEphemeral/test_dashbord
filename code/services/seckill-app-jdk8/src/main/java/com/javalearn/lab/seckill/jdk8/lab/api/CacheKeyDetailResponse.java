package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * {@code POST /lab/cache/key}: the L2 value (byte-capped), its TTL and whether
 * the same product id is present in the instance-local L1.
 *
 * <p>The L1 flag is a non-counting peek: inspecting a key must not change the
 * hit/miss counters the experiment reads. A key absent from both layers is a
 * 404 instead of this payload.</p>
 */
public final class CacheKeyDetailResponse {

    private final String key;
    private final long productId;
    private final boolean present;
    private final boolean l2Present;
    private final boolean l1Present;
    private final Long ttlSeconds;
    private final String value;
    private final boolean truncated;
    private final int valueBytes;
    private final int valueMaxBytes;

    public CacheKeyDetailResponse(String key,
                                  long productId,
                                  boolean present,
                                  boolean l2Present,
                                  boolean l1Present,
                                  Long ttlSeconds,
                                  String value,
                                  boolean truncated,
                                  int valueBytes,
                                  int valueMaxBytes) {
        this.key = key;
        this.productId = productId;
        this.present = present;
        this.l2Present = l2Present;
        this.l1Present = l1Present;
        this.ttlSeconds = ttlSeconds;
        this.value = value;
        this.truncated = truncated;
        this.valueBytes = valueBytes;
        this.valueMaxBytes = valueMaxBytes;
    }

    public String getKey() {
        return key;
    }

    public long getProductId() {
        return productId;
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isL2Present() {
        return l2Present;
    }

    public boolean isL1Present() {
        return l1Present;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public String getValue() {
        return value;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int getValueBytes() {
        return valueBytes;
    }

    public int getValueMaxBytes() {
        return valueMaxBytes;
    }

    public String key() {
        return key;
    }

    public long productId() {
        return productId;
    }

    public boolean l2Present() {
        return l2Present;
    }

    public boolean l1Present() {
        return l1Present;
    }

    public Long ttlSeconds() {
        return ttlSeconds;
    }

    public String value() {
        return value;
    }

    public boolean truncated() {
        return truncated;
    }

    public int valueBytes() {
        return valueBytes;
    }

    public int valueMaxBytes() {
        return valueMaxBytes;
    }
}
