package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * {@code POST /lab/cache/seed} payload: what the experiment dataset looks like
 * after this call.
 *
 * <p>{@code inserted} is zero on every call after the first, which is how the
 * operator sees the seed is idempotent; {@code rowsInRange} and
 * {@code avalancheKeysWritten} report the dataset size as it really is now, so a
 * partial Redis failure is visible instead of being silently reported as success.
 * Never contains a secret.</p>
 */
public final class CacheSeedResponse {

    private final long startId;
    private final long endId;
    private final long requestedProducts;
    private final long inserted;
    private final long alreadyPresent;
    private final long rowsInRange;
    private final long avalancheKeysWritten;
    private final long avalancheTtlSeconds;
    private final long hotKeyProductId;
    private final boolean hotKeyWritten;
    private final long bloomIdsAdded;
    private final boolean bloomInitialised;
    private final String mode;

    public CacheSeedResponse(long startId,
                             long endId,
                             long requestedProducts,
                             long inserted,
                             long alreadyPresent,
                             long rowsInRange,
                             long avalancheKeysWritten,
                             long avalancheTtlSeconds,
                             long hotKeyProductId,
                             boolean hotKeyWritten,
                             long bloomIdsAdded,
                             boolean bloomInitialised,
                             String mode) {
        this.startId = startId;
        this.endId = endId;
        this.requestedProducts = requestedProducts;
        this.inserted = inserted;
        this.alreadyPresent = alreadyPresent;
        this.rowsInRange = rowsInRange;
        this.avalancheKeysWritten = avalancheKeysWritten;
        this.avalancheTtlSeconds = avalancheTtlSeconds;
        this.hotKeyProductId = hotKeyProductId;
        this.hotKeyWritten = hotKeyWritten;
        this.bloomIdsAdded = bloomIdsAdded;
        this.bloomInitialised = bloomInitialised;
        this.mode = mode;
    }

    public long getStartId() {
        return startId;
    }

    public long getEndId() {
        return endId;
    }

    public long getRequestedProducts() {
        return requestedProducts;
    }

    public long getInserted() {
        return inserted;
    }

    public long getAlreadyPresent() {
        return alreadyPresent;
    }

    public long getRowsInRange() {
        return rowsInRange;
    }

    public long getAvalancheKeysWritten() {
        return avalancheKeysWritten;
    }

    public long getAvalancheTtlSeconds() {
        return avalancheTtlSeconds;
    }

    public long getHotKeyProductId() {
        return hotKeyProductId;
    }

    public boolean isHotKeyWritten() {
        return hotKeyWritten;
    }

    public long getBloomIdsAdded() {
        return bloomIdsAdded;
    }

    public boolean isBloomInitialised() {
        return bloomInitialised;
    }

    public String getMode() {
        return mode;
    }

    public long startId() {
        return startId;
    }

    public long endId() {
        return endId;
    }

    public long requestedProducts() {
        return requestedProducts;
    }

    public long inserted() {
        return inserted;
    }

    public long alreadyPresent() {
        return alreadyPresent;
    }

    public long rowsInRange() {
        return rowsInRange;
    }

    public long avalancheKeysWritten() {
        return avalancheKeysWritten;
    }

    public long avalancheTtlSeconds() {
        return avalancheTtlSeconds;
    }

    public long hotKeyProductId() {
        return hotKeyProductId;
    }

    public boolean hotKeyWritten() {
        return hotKeyWritten;
    }

    public long bloomIdsAdded() {
        return bloomIdsAdded;
    }

    public boolean bloomInitialised() {
        return bloomInitialised;
    }

    public String mode() {
        return mode;
    }
}
