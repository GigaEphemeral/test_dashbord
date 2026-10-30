package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two clocks of the logical-expire store: the logical deadline travels
 * inside the entry (compared against the injected wall clock by the guard),
 * the physical retention is enforced by Caffeine against an injectable ticker.
 */
class LogicalStaleStoreTest {

    private static final ProductResponse VALUE = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");

    @Test
    void putCarriesTheLogicalDeadlineForwardFromNow() {
        AtomicLong ticker = new AtomicLong();
        LogicalStaleStore store = new LogicalStaleStore(10L,
                Duration.ofSeconds(120), Duration.ofSeconds(30), ticker::get);

        store.put(1L, VALUE, 1_000_000L);

        LogicalStaleStore.StaleValue entry = store.find(1L).orElseThrow(IllegalStateException::new);
        assertThat(entry.value()).isEqualTo(VALUE);
        assertThat(entry.logicalExpireAtMillis()).isEqualTo(1_030_000L);
    }

    @Test
    void theEntrySurvivesTheLogicalDeadlineButNotThePhysicalRetention() {
        AtomicLong ticker = new AtomicLong();
        LogicalStaleStore store = new LogicalStaleStore(10L,
                Duration.ofSeconds(120), Duration.ofSeconds(30), ticker::get);
        store.put(1L, VALUE, 0L);

        // 31s later: logically stale — that is exactly when it must still be
        // there, because serving it IS the strategy.
        ticker.set(TimeUnit.SECONDS.toNanos(31));
        assertThat(store.find(1L)).isPresent();

        // 121s: past the physical window; no eternally stale value.
        ticker.set(TimeUnit.SECONDS.toNanos(121));
        assertThat(store.find(1L)).isEmpty();
    }

    @Test
    void theStoreIsBounded() {
        AtomicLong ticker = new AtomicLong();
        LogicalStaleStore store = new LogicalStaleStore(2L,
                Duration.ofSeconds(120), Duration.ofSeconds(30), ticker::get);

        store.put(1L, VALUE, 0L);
        store.put(2L, VALUE, 0L);
        store.put(3L, VALUE, 0L);

        assertThat(store.size()).isLessThanOrEqualTo(2L);
    }

    @Test
    void aRetentionThatDoesNotOutliveTheLogicalTtlIsRefusedAtWiringTime() {
        assertThatThrownBy(() -> new LogicalStaleStore(10L,
                Duration.ofSeconds(10), Duration.ofSeconds(30), System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must exceed the logical ttl");
        assertThatThrownBy(() -> new LogicalStaleStore(0L,
                Duration.ofSeconds(120), Duration.ofSeconds(30), System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntries");
    }
}
