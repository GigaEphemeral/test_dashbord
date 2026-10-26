package com.javalearn.lab.seckill.catalog.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bloom half of the penetration defense, and specifically the rule that only
 * a proven negative may skip the database.
 *
 * <p>Redisson is mocked: what must be pinned here is the service's answer to a
 * false positive, an unreadable filter and a failed re-init, none of which a
 * real filter can be forced into on demand.</p>
 */
class ProductBloomFilterServiceTest {

    private static final String FILTER_KEY = "bloom:product-ids";
    private static final long EXPECTED_INSERTIONS = 10_000L;
    private static final double FALSE_PROBABILITY = 0.01D;

    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBloomFilter<Long> filter = mock(RBloomFilter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ProductBloomFilterService service;

    @BeforeEach
    void setUp() {
        when(redisson.<Long>getBloomFilter(FILTER_KEY)).thenReturn(filter);
        service = new ProductBloomFilterService(redisson, FILTER_KEY, EXPECTED_INSERTIONS,
                FALSE_PROBABILITY, registry);
    }

    @Test
    void aPositiveAnswerMeansQueryTheDatabase() {
        when(filter.contains(1L)).thenReturn(true);

        assertThat(service.mightContain(1L)).isTrue();
        assertThat(counter("hit")).isEqualTo(1.0);
    }

    @Test
    void onlyAPrevenNegativeLetsTheCallerSkipTheDatabase() {
        when(filter.contains(404L)).thenReturn(false);

        assertThat(service.mightContain(404L)).isFalse();
        assertThat(counter("absent")).isEqualTo(1.0);
    }

    @Test
    void anUnavailableFilterIsTreatedAsPossiblyPresent() {
        when(filter.contains(any(Long.class))).thenThrow(new IllegalStateException("redis is down"));

        assertThat(service.mightContain(404L)).isTrue();
        assertThat(counter("error")).isEqualTo(1.0);
    }

    @Test
    void aRebuildReplacesTheFilterWithTheIdsThatExist() {
        when(filter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY)).thenReturn(true);
        when(filter.add(anyCollection())).thenReturn(3L);

        assertThat(service.rebuild(List.of(1L, 20L, 90_000L))).isEqualTo(3L);

        verify(filter).delete();
        verify(filter).tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
        verify(filter).add(eq(List.of(1L, 20L, 90_000L)));
    }

    @Test
    void aFilterThatCannotBeInitialisedIsReportedInsteadOfHalfBuilt() {
        when(filter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY)).thenReturn(false);

        assertThatThrownBy(() -> service.rebuild(List.of(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be initialized");
        verify(filter, never()).add(anyCollection());
    }

    @Test
    void statsReportTheLiveGeometry() {
        when(filter.count()).thenReturn(7L);
        when(filter.getExpectedInsertions()).thenReturn(EXPECTED_INSERTIONS);
        when(filter.getFalseProbability()).thenReturn(FALSE_PROBABILITY);
        when(filter.getHashIterations()).thenReturn(5);

        assertThat(service.stats()).isEqualTo(
                new ProductBloomFilterService.Stats(true, 7L, EXPECTED_INSERTIONS, FALSE_PROBABILITY, 5));
    }

    @Test
    void anUnreadableFilterIsReportedAsUninitialised() {
        when(redisson.<Long>getBloomFilter(anyString())).thenThrow(new IllegalStateException("no redis"));

        assertThat(service.stats()).isEqualTo(new ProductBloomFilterService.Stats(
                false, 0L, EXPECTED_INSERTIONS, FALSE_PROBABILITY, 0));
        assertThat(service.key()).isEqualTo(FILTER_KEY);
    }

    private double counter(String result) {
        return registry.get("cache_bloom_requests").tag("cache", "product").tag("result", result)
                .counter().count();
    }
}
