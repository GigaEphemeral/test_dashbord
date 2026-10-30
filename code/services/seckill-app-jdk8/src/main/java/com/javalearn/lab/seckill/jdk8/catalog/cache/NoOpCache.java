package com.javalearn.lab.seckill.jdk8.catalog.cache;

import org.springframework.cache.Cache;

import java.util.concurrent.Callable;

/**
 * A cache that never stores anything, returned by the toggle-aware
 * {@link TwoLevelCacheManager} while the {@code cache} lab toggle is off.
 *
 * <p>Spring's cache aspect still runs its normal get -> invoke -> put cycle,
 * but every read misses and every write is discarded, so neither Caffeine L1
 * nor Redis L2 is touched and the source of truth is called on every request.
 * Re-enabling the toggle immediately returns the real {@link TwoLevelCache}
 * again; no controller or {@code @Cacheable} contract changes.</p>
 */
final class NoOpCache implements Cache {

    private final String name;

    NoOpCache(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        return null;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return null;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        try {
            return valueLoader.call();
        } catch (Exception exception) {
            throw new ValueRetrievalException(key, valueLoader, exception);
        }
    }

    @Override
    public void put(Object key, Object value) {
        // Intentionally discarded: the toggle must bypass both cache levels.
    }

    @Override
    public void evict(Object key) {
        // Nothing is stored, so there is nothing to evict.
    }

    @Override
    public void clear() {
        // Nothing is stored, so there is nothing to clear.
    }
}
