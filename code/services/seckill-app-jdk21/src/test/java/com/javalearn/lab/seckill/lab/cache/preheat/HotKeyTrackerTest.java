package com.javalearn.lab.seckill.lab.cache.preheat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deterministic hot-key detection: halving decay on a controllable clock,
 * Top-N selection/tie-break, the Caffeine cap, and the Redis publish tolerance
 * contract ({@code cache_hotkey_publish{result="failure"}} instead of a throw).
 */
class HotKeyTrackerTest {

    private static final String HOT_KEY = "hot:products";

    private SimpleMeterRegistry registry;
    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zset;
    private PreheatTestSupport.MutableClock clock;
    private LabCachePreheatProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        redisTemplate = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zset);
        clock = PreheatTestSupport.clock(0L);
        properties = TestLabCachePreheatProperties.defaults();
    }

    @Test
    void countersHalveEveryDecayInterval() {
        HotKeyTracker tracker = tracker();

        for (int i = 0; i < 4; i++) {
            tracker.record(1L);
        }
        tracker.record(2L);
        tracker.record(2L);

        assertThat(tracker.topN())
                .containsExactly(new HotKeyTracker.ScoredProduct(1L, 4L),
                        new HotKeyTracker.ScoredProduct(2L, 2L));

        clock.advance(Duration.ofSeconds(TestLabCachePreheatProperties.DECAY_SECONDS));
        assertThat(tracker.topN())
                .containsExactly(new HotKeyTracker.ScoredProduct(1L, 2L),
                        new HotKeyTracker.ScoredProduct(2L, 1L));

        clock.advance(Duration.ofSeconds(TestLabCachePreheatProperties.DECAY_SECONDS));
        assertThat(tracker.topN())
                .containsExactly(new HotKeyTracker.ScoredProduct(1L, 1L));

        clock.advance(Duration.ofSeconds(2 * TestLabCachePreheatProperties.DECAY_SECONDS));
        assertThat(tracker.topN()).isEmpty();
    }

    @Test
    void topNIsCappedAndTiesBreakByProductId() {
        properties.setTopN(3L);
        HotKeyTracker tracker = tracker();

        tracker.record(30L);
        tracker.record(30L);
        tracker.record(20L);
        tracker.record(20L);
        tracker.record(40L);
        for (int i = 0; i < 3; i++) {
            tracker.record(10L);
        }

        List<HotKeyTracker.ScoredProduct> top = tracker.topN();

        assertThat(top).containsExactly(
                new HotKeyTracker.ScoredProduct(10L, 3L),
                new HotKeyTracker.ScoredProduct(20L, 2L),
                new HotKeyTracker.ScoredProduct(30L, 2L));
    }

    @Test
    void theCaffeineCapBoundsTheTrackedKeySet() {
        properties.getTracker().setCapacity(2L);
        HotKeyTracker tracker = tracker();

        for (long id = 1L; id <= 50L; id++) {
            tracker.record(id);
        }

        assertThat(tracker.trackedKeys()).isLessThanOrEqualTo(2L);
        assertThat(tracker.topN()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void publishReplacesTheSharedZSetWithScoredTopN() {
        when(zset.zCard(HOT_KEY)).thenReturn(2L);
        HotKeyTracker tracker = tracker();
        tracker.record(7L);
        tracker.record(7L);
        tracker.record(9L);

        int published = tracker.publishTopN();

        assertThat(published).isEqualTo(2);
        verify(redisTemplate).delete(HOT_KEY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> tuples =
                ArgumentCaptor.forClass(Set.class);
        verify(zset).add(eq(HOT_KEY), tuples.capture());
        Map<String, Double> scores = tuples.getValue().stream()
                .collect(Collectors.toMap(ZSetOperations.TypedTuple::getValue,
                        ZSetOperations.TypedTuple::getScore));
        assertThat(scores).containsEntry("7", 2.0).containsEntry("9", 1.0);

        assertThat(counter("cache_hotkey_publish", "result", "success")).isEqualTo(1.0);
        assertThat(counter("cache_hotkey_publish", "result", "failure")).isZero();
        assertThat(registry.find("cache_preheat_hot_list_size").gauge().value()).isEqualTo(2.0);
        assertThat(tracker.hotListSize()).isEqualTo(2L);
    }

    @Test
    void aRedisOutageDuringPublishIsCountedAndSwallowed() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(zset).add(eq(HOT_KEY), anySet());
        HotKeyTracker tracker = tracker();
        tracker.record(1L);

        assertThatCode(tracker::publishTopN).doesNotThrowAnyException();

        assertThat(counter("cache_hotkey_publish", "result", "failure")).isEqualTo(1.0);
        assertThat(counter("cache_hotkey_publish", "result", "success")).isZero();
    }

    @Test
    void aRedisOutageWhileReadingTheSizeKeepsTheLastKnownValue() {
        when(zset.zCard(HOT_KEY))
                .thenReturn(4L)
                .thenThrow(new RedisConnectionFailureException("redis down"));
        HotKeyTracker tracker = tracker();
        tracker.record(1L);

        assertThat(tracker.publishTopN()).isEqualTo(1);
        assertThat(tracker.refreshHotListSize()).isEqualTo(4L);

        assertThat(tracker.refreshHotListSize()).isEqualTo(4L);
        assertThat(tracker.hotListSize()).isEqualTo(4L);
    }

    private HotKeyTracker tracker() {
        return new HotKeyTracker(properties, redisTemplate, registry, clock);
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
