package com.javalearn.lab.seckill.catalog.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Redis half of the TTL seam: the jitter must be relative to the TTL the
 * two-level cache actually wrote, and a non-product key or a vanished entry must
 * produce "nothing to do" rather than a bogus rewrite.
 */
class RedisL2TtlAdjusterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisL2TtlAdjuster adjuster = new RedisL2TtlAdjuster(redis,
            TwoLevelCacheSettings.defaults().l2PrefixFor(TwoLevelCacheManager.PRODUCT_CACHE));

    @Test
    void readsTheRemainingTtlOfTheProductKeyTheCacheWrote() {
        when(redis.getExpire("cache:product:90001", TimeUnit.SECONDS)).thenReturn(60L);

        assertThat(adjuster.remainingTtl(90_001L)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aKeyThatIsNotAProductIdHasNoTtlAndNoRedisCall() {
        assertThat(adjuster.remainingTtl("90001")).isNull();

        verifyNoInteractions(redis);
    }

    @Test
    void aMissingOrPersistentKeyHasNoTtlToJitter() {
        when(redis.getExpire("cache:product:1", TimeUnit.SECONDS)).thenReturn(0L);
        when(redis.getExpire("cache:product:2", TimeUnit.SECONDS)).thenReturn(-1L);
        when(redis.getExpire("cache:product:3", TimeUnit.SECONDS)).thenReturn(null);

        assertThat(adjuster.remainingTtl(1L)).isNull();
        assertThat(adjuster.remainingTtl(2L)).isNull();
        assertThat(adjuster.remainingTtl(3L)).isNull();
    }

    @Test
    void rewritesTheTtlOfTheSameKeyTheCacheWrote() {
        adjuster.rewrite(90_001L, Duration.ofSeconds(48));

        verify(redis).expire("cache:product:90001", Duration.ofSeconds(48));
    }

    @Test
    void aNonProductKeyIsNeverRewritten() {
        adjuster.rewrite("90001", Duration.ofSeconds(48));

        verifyNoInteractions(redis);
    }
}
