package com.javalearn.lab.seckill.jdk8.catalog.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * The TTL spread behind the {@code ttl-jitter} avalanche strategy (design 8.4
 * 雪崩 row): every product-cache L2 write gets a TTL drawn uniformly from
 * {@code base ± percent}, so the 200 experiment keys that share one TTL do not
 * expire in the same second and their misses do not stampede the database.
 *
 * <p>The random source is injected ({@code DoubleSupplier} returning
 * {@code [0,1)}) so the distribution can be asserted deterministically in tests;
 * production wires {@code ThreadLocalRandom}. The result is whole seconds
 * (the unit Redis {@code EXPIRE} and the seeder use) and never below one
 * second — a zero TTL would delete the entry instead of jittering it.</p>
 */
public final class TtlJitter {

    private final double percent;
    private final DoubleSupplier random;

    public TtlJitter(double percent, DoubleSupplier random) {
        if (!(percent >= 0D) || percent >= 100D) {
            throw new IllegalArgumentException("TTL jitter percent must be in [0, 100): " + percent);
        }
        this.percent = percent;
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @return the base TTL scaled by a uniform factor in
     *         {@code [1 - percent, 1 + percent]}, in whole seconds, at least 1s
     */
    public Duration jitter(Duration baseTtl) {
        Objects.requireNonNull(baseTtl, "baseTtl");
        long baseMillis = baseTtl.toMillis();
        if (baseMillis <= 0L) {
            throw new IllegalArgumentException("Base TTL must be positive: " + baseTtl);
        }
        double factor = 1D + (random.getAsDouble() * 2D - 1D) * (percent / 100D);
        long seconds = Math.max(1L, Math.round((baseMillis / 1000D) * factor));
        return Duration.ofSeconds(seconds);
    }

    public double percent() {
        return percent;
    }
}
