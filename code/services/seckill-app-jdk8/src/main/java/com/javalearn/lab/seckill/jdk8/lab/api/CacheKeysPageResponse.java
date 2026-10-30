package com.javalearn.lab.seckill.jdk8.lab.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
public final class CacheKeysPageResponse {

    private final String cursor;
    private final List<Item> items;
    private final long scanned;
    private final int limit;

    public CacheKeysPageResponse(String cursor, List<Item> items, long scanned, int limit) {
        this.cursor = cursor;
        this.items = Collections.unmodifiableList(items);
        this.scanned = scanned;
        this.limit = limit;
    }

    public String getCursor() {
        return cursor;
    }

    public List<Item> getItems() {
        return items;
    }

    public long getScanned() {
        return scanned;
    }

    public int getLimit() {
        return limit;
    }

    public String cursor() {
        return cursor;
    }

    public List<Item> items() {
        return items;
    }

    public long scanned() {
        return scanned;
    }

    public int limit() {
        return limit;
    }

    /** One SCAN result: the Redis key and its remaining TTL in seconds. */
    public static final class Item {

        private final String key;
        private final long ttlSeconds;

        public Item(String key, long ttlSeconds) {
            this.key = key;
            this.ttlSeconds = ttlSeconds;
        }

        public String getKey() {
            return key;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public String key() {
            return key;
        }

        public long ttlSeconds() {
            return ttlSeconds;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Item)) {
                return false;
            }
            Item that = (Item) other;
            return ttlSeconds == that.ttlSeconds && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, ttlSeconds);
        }

        @Override
        public String toString() {
            return "Item{key='" + key + "', ttlSeconds=" + ttlSeconds + '}';
        }
    }
}
