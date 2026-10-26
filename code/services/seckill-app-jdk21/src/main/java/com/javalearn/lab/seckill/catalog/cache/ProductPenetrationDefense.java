package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.Optional;

/**
 * Cache-penetration stage of the product read path:
 * L1 Caffeine -&gt; L2 Redis -&gt; <em>this guard</em> -&gt; database.
 *
 * <p>It runs only on an L1+L2 miss, so a normal product read is unchanged. The
 * mode is read per call from {@link CacheDefenseModeService}, which is what makes
 * the protection runtime-switchable without a restart.</p>
 *
 * <p>The guard answers {@link Optional#empty()} for "absent" and lets the caller
 * turn that into the standard 404; the database contract (and its exception) is
 * never replaced. That is what keeps a bloom false positive a 404 instead of a
 * 500.</p>
 */
public final class ProductPenetrationDefense {

    private final CacheDefenseModeService modeService;
    private final NullValueMarkerStore markerStore;
    private final ProductBloomFilterService bloomFilter;
    private final Counter dbFallbacks;

    public ProductPenetrationDefense(CacheDefenseModeService modeService,
                                     NullValueMarkerStore markerStore,
                                     ProductBloomFilterService bloomFilter,
                                     MeterRegistry meterRegistry) {
        this.modeService = Objects.requireNonNull(modeService, "modeService");
        this.markerStore = Objects.requireNonNull(markerStore, "markerStore");
        this.bloomFilter = Objects.requireNonNull(bloomFilter, "bloomFilter");
        // Second value of the existing cache_db_fallbacks family: reason=miss is
        // "L1+L2 had nothing", reason=penetration is "protection was on and the
        // database was queried anyway", which is the number the experiment drives
        // towards zero.
        this.dbFallbacks = Counter.builder("cache_db_fallbacks")
                .tag("cache", TwoLevelCacheManager.PRODUCT_CACHE)
                .tag("reason", "penetration")
                .register(meterRegistry);
    }

    public Optional<ProductResponse> load(long id, ProductFinder finder) {
        Objects.requireNonNull(finder, "finder");
        return switch (modeService.current()) {
            case OFF -> finder.find(id);
            case NULL_CACHE -> withNullCache(id, finder);
            case BLOOM -> withBloom(id, finder);
        };
    }

    public CacheDefenseMode mode() {
        return modeService.current();
    }

    private Optional<ProductResponse> withNullCache(long id, ProductFinder finder) {
        if (markerStore.isMarkedAbsent(id)) {
            return Optional.empty();
        }
        dbFallbacks.increment();
        Optional<ProductResponse> found = finder.find(id);
        if (found.isEmpty()) {
            markerStore.markAbsent(id);
        }
        return found;
    }

    private Optional<ProductResponse> withBloom(long id, ProductFinder finder) {
        if (!bloomFilter.mightContain(id)) {
            return Optional.empty();
        }
        dbFallbacks.increment();
        return finder.find(id);
    }

    /**
     * The source behind the guard. Implementations return an empty Optional for
     * "no such product" and must propagate any other failure untouched.
     */
    @FunctionalInterface
    public interface ProductFinder {

        Optional<ProductResponse> find(long id);
    }
}
