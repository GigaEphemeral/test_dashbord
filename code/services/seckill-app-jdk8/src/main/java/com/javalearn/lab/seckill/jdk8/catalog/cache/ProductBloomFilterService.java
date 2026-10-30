package com.javalearn.lab.seckill.jdk8.catalog.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;

/**
 * Redisson bloom filter over the ids that actually exist in
 * {@code catalog_product} (cache penetration).
 *
 * <p>The filter answers "definitely absent" only, so the lab rule is that any
 * answer other than a clean negative must fall through to the database:</p>
 * <ul>
 *   <li>a hit (possibly present, including a false positive) queries the
 *       database and keeps the normal 404 contract;</li>
 *   <li>a Redis failure, or a filter that was never initialised, is treated as
 *       "possibly present" for the same reason. It is counted as
 *       {@code cache_bloom_requests{result="error"}} and never surfaces as a
 *       500.</li>
 * </ul>
 *
 * <p>{@link #rebuild} deliberately covers <em>every</em> product id, not only
 * the experiment range: a real demo product missing from the filter would be
 * answered "definitely absent" and turned into a wrong 404.</p>
 */
public final class ProductBloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(ProductBloomFilterService.class);

    private final RedissonClient redissonClient;
    private final String key;
    private final long expectedInsertions;
    private final double falseProbability;

    private final Counter filterHits;
    private final Counter filterAbsents;
    private final Counter filterErrors;

    public ProductBloomFilterService(RedissonClient redissonClient,
                                     String key,
                                     long expectedInsertions,
                                     double falseProbability,
                                     MeterRegistry meterRegistry) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.key = Objects.requireNonNull(key, "key");
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
        String cache = TwoLevelCacheManager.PRODUCT_CACHE;
        // result="hit" means "the filter cannot rule this id out", i.e. a database
        // read follows. It is not a cache hit.
        this.filterHits = Counter.builder("cache_bloom_requests")
                .tag("cache", cache).tag("result", "hit")
                .register(meterRegistry);
        this.filterAbsents = Counter.builder("cache_bloom_requests")
                .tag("cache", cache).tag("result", "absent")
                .register(meterRegistry);
        this.filterErrors = Counter.builder("cache_bloom_requests")
                .tag("cache", cache).tag("result", "error")
                .register(meterRegistry);
    }

    /**
     * @return {@code true} when the id may exist and the caller must query the
     *         database; {@code false} only when the filter proves it absent
     */
    public boolean mightContain(long id) {
        boolean present;
        try {
            present = filter().contains(Long.valueOf(id));
        } catch (RuntimeException ex) {
            filterErrors.increment();
            log.warn("Bloom filter '{}' unavailable ({}); falling back to the source",
                    key, ex.toString());
            return true;
        }
        if (present) {
            filterHits.increment();
        } else {
            filterAbsents.increment();
        }
        return present;
    }

    /**
     * Replaces the filter with one built from {@code existingIds}.
     *
     * <p>Delete-then-init is the only way Redisson can re-derive the bit-array
     * geometry; adding to an existing filter would keep counting the ids that
     * were already there and inflate {@code size}. The rebuild window is short
     * and, because a filter that is not initialised makes {@link #mightContain}
     * fall through to the database, it can only ever cost protection, never
     * produce a wrong 404.</p>
     *
     * @return the number of ids added
     */
    public long rebuild(Collection<Long> existingIds) {
        RBloomFilter<Long> bloom = filter();
        bloom.delete();
        if (!bloom.tryInit(expectedInsertions, falseProbability)) {
            throw new IllegalStateException(
                    "Bloom filter '" + key + "' could not be initialized (capacity "
                            + expectedInsertions + ", ids to insert " + existingIds.size() + ")");
        }
        long added = bloom.add(existingIds);
        log.info("Bloom filter '{}' rebuilt with {} id(s), capacity {}, fpp {}",
                key, added, expectedInsertions, falseProbability);
        return added;
    }

    public Stats stats() {
        try {
            RBloomFilter<Long> bloom = filter();
            return new Stats(true, bloom.count(), bloom.getExpectedInsertions(),
                    bloom.getFalseProbability(), bloom.getHashIterations());
        } catch (RuntimeException ex) {
            log.warn("Bloom filter '{}' is not readable ({}); reporting it as uninitialised",
                    key, ex.toString());
            return new Stats(false, 0L, expectedInsertions, falseProbability, 0);
        }
    }

    public String key() {
        return key;
    }

    private RBloomFilter<Long> filter() {
        return redissonClient.getBloomFilter(key);
    }

    /**
     * Diagnostic view of the filter; {@code initialised} is false when Redis or
     * the key itself is unavailable, which the mode endpoint reports so an
     * operator can see that {@code bloom} is protecting nothing right now.
     */
    public static final class Stats {

        private final boolean initialised;
        private final long size;
        private final long capacity;
        private final double falseProbability;
        private final int hashIterations;

        public Stats(boolean initialised, long size, long capacity,
                     double falseProbability, int hashIterations) {
            this.initialised = initialised;
            this.size = size;
            this.capacity = capacity;
            this.falseProbability = falseProbability;
            this.hashIterations = hashIterations;
        }

        public boolean initialised() {
            return initialised;
        }

        public long size() {
            return size;
        }

        public long capacity() {
            return capacity;
        }

        public double falseProbability() {
            return falseProbability;
        }

        public int hashIterations() {
            return hashIterations;
        }

        public boolean isInitialised() {
            return initialised;
        }

        public long getSize() {
            return size;
        }

        public long getCapacity() {
            return capacity;
        }

        public double getFalseProbability() {
            return falseProbability;
        }

        public int getHashIterations() {
            return hashIterations;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Stats)) {
                return false;
            }
            Stats that = (Stats) other;
            return initialised == that.initialised
                    && size == that.size
                    && capacity == that.capacity
                    && hashIterations == that.hashIterations
                    && Double.compare(that.falseProbability, falseProbability) == 0;
        }

        @Override
        public int hashCode() {
            int result = initialised ? 1 : 0;
            result = 31 * result + (int) (size ^ (size >>> 32));
            result = 31 * result + (int) (capacity ^ (capacity >>> 32));
            long probabilityBits = Double.doubleToLongBits(falseProbability);
            result = 31 * result + (int) (probabilityBits ^ (probabilityBits >>> 32));
            result = 31 * result + hashIterations;
            return result;
        }

        @Override
        public String toString() {
            return "Stats{initialised=" + initialised + ", size=" + size
                    + ", capacity=" + capacity + ", falseProbability=" + falseProbability
                    + ", hashIterations=" + hashIterations + '}';
        }
    }
}
