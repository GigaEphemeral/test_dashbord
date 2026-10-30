package com.javalearn.lab.seckill.jdk8.catalog.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The null-cache half of the penetration defense: a miss becomes a short-lived,
 * jittered Redis marker instead of a permanent hole in the cache.
 *
 * <p>The TTL assertions use a fixed jitter source so the arithmetic is
 * observable; {@code randomJitterSeconds} is asserted separately for its bounds.
 * All cases run against a mocked Redis because the point of this store is that a
 * Redis outage must degrade to "ask the database", never to a 500.</p>
 */
class NullValueMarkerStoreTest {

    private static final String KEY_PREFIX = "null:product:";
    private static final Duration BASE_TTL = Duration.ofSeconds(10);
    private static final Duration JITTER = Duration.ofSeconds(5);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void wireTemplate() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void aLiveMarkerIsReportedAsAbsent() {
        when(values.get("null:product:404")).thenReturn(NullValueMarkerStore.MARKER_VALUE);
        NullValueMarkerStore store = store(() -> 0L);

        assertThat(store.isMarkedAbsent(404L)).isTrue();
        assertThat(counter("cache_null_cache_requests", "hit")).isEqualTo(1.0);
        assertThat(counter("cache_null_cache_requests", "miss")).isZero();
    }

    @Test
    void noMarkerMeansTheSourceStillHasToBeAsked() {
        when(values.get("null:product:404")).thenReturn(null);
        NullValueMarkerStore store = store(() -> 0L);

        assertThat(store.isMarkedAbsent(404L)).isFalse();
        assertThat(counter("cache_null_cache_requests", "miss")).isEqualTo(1.0);
    }

    @Test
    void aMissIsWrittenWithBaseTtlPlusJitter() {
        NullValueMarkerStore store = store(() -> 7L);

        store.markAbsent(404L);

        verify(values).set("null:product:404", "NULL", Duration.ofSeconds(17));
        assertThat(store.entryTtl()).isEqualTo(Duration.ofSeconds(17));
        assertThat(counter("cache_null_cache_writes", null)).isEqualTo(1.0);
    }

    @Test
    void theJitterIsDrawnPerWriteSoMarkersDoNotExpireTogether() {
        AtomicInteger draws = new AtomicInteger();
        NullValueMarkerStore store = store(() -> draws.getAndIncrement() == 0 ? 0L : 5L);

        store.markAbsent(1L);
        store.markAbsent(2L);

        verify(values).set(eq("null:product:1"), anyString(), eq(Duration.ofSeconds(10)));
        verify(values).set(eq("null:product:2"), anyString(), eq(Duration.ofSeconds(15)));
    }

    @Test
    void randomJitterStaysInsideTheConfiguredWindow() {
        LongSupplier jitter = NullValueMarkerStore.randomJitterSeconds(JITTER);

        for (int draw = 0; draw < 200; draw++) {
            long seconds = jitter.getAsLong();
            assertThat(seconds).isBetween(0L, JITTER.getSeconds());
        }
        assertThat(NullValueMarkerStore.randomJitterSeconds(Duration.ZERO).getAsLong()).isZero();
    }

    @Test
    void aMarkerReadFailureDegradesToNoMarker() {
        when(values.get(any())).thenThrow(new RedisConnectionFailureException("redis is down"));
        NullValueMarkerStore store = store(() -> 0L);

        assertThat(store.isMarkedAbsent(404L)).isFalse();
        assertThat(counter("cache_null_cache_errors", null)).isEqualTo(1.0);
    }

    @Test
    void aMarkerWriteFailureNeverEscapes() {
        doThrow(new RedisConnectionFailureException("redis is down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        NullValueMarkerStore store = store(() -> 0L);

        assertThatCode(() -> store.markAbsent(404L)).doesNotThrowAnyException();
        assertThat(counter("cache_null_cache_errors", null)).isEqualTo(1.0);
        assertThat(counter("cache_null_cache_writes", null)).isZero();
    }

    @Test
    void theKeyLayoutAndGeometryAreExposedForTheStateView() {
        NullValueMarkerStore store = store(() -> 3L);

        assertThat(store.keyPrefix()).isEqualTo(KEY_PREFIX);
        assertThat(store.redisKey(404L)).isEqualTo("null:product:404");
        assertThat(store.baseTtl()).isEqualTo(BASE_TTL);
        assertThat(store.jitter()).isEqualTo(JITTER);
    }

    private NullValueMarkerStore store(LongSupplier jitterSeconds) {
        return new NullValueMarkerStore(redis, KEY_PREFIX, BASE_TTL, JITTER, jitterSeconds, registry);
    }

    private double counter(String name, String result) {
        return registry.get(name).tag("cache", "product")
                .tags(result == null ? new String[0] : new String[] {"result", result})
                .counter().count();
    }}
