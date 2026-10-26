package com.javalearn.lab.seckill.admission.infra;

import com.javalearn.lab.seckill.RedisTestContainer;
import com.javalearn.lab.seckill.admission.app.SeckillStockStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Redis proof that the configured Lua script checks and decrements
 * atomically, plus the seed/restore semantics used by startup and compensation.
 */
class SeckillStockStoreIT {

    private static final long PRODUCT_ID = 20L;
    private static final String PREFIX = "test:seckill:stock:";
    private static final String KEY = PREFIX + PRODUCT_ID;

    private StringRedisTemplate redisTemplate;
    private RedisSeckillStockStore stockStore;

    @BeforeEach
    void setUp() {
        redisTemplate = RedisTestContainer.newStringRedisTemplate();
        stockStore = new RedisSeckillStockStore(redisTemplate, PREFIX, "lua/seckill_stock.lua");
        redisTemplate.delete(KEY);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
    }

    @Test
    void missingKeyIsReportedWithoutCreatingIt() {
        SeckillStockStore.Reservation reservation = stockStore.reserve(PRODUCT_ID, 1);

        assertThat(reservation.status()).isEqualTo(SeckillStockStore.ReservationStatus.MISSING);
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    void reservationIsAtomicAndNeverGoesNegative() {
        assertThat(stockStore.seedIfAbsent(PRODUCT_ID, 5)).isTrue();

        assertThat(stockStore.reserve(PRODUCT_ID, 2).status())
                .isEqualTo(SeckillStockStore.ReservationStatus.RESERVED);
        assertThat(stockStore.reserve(PRODUCT_ID, 4).status())
                .isEqualTo(SeckillStockStore.ReservationStatus.SOLD_OUT);
        assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("3");
    }

    @Test
    void seedNeverOverwritesAnExistingKey() {
        assertThat(stockStore.seedIfAbsent(PRODUCT_ID, 5)).isTrue();
        assertThat(stockStore.seedIfAbsent(PRODUCT_ID, 100)).isFalse();

        assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("5");
    }

    @Test
    void restoreAddsBackOnlyWhenTheKeyExists() {
        assertThat(stockStore.seedIfAbsent(PRODUCT_ID, 5)).isTrue();
        stockStore.reserve(PRODUCT_ID, 3);

        assertThat(stockStore.restore(PRODUCT_ID, 2)).isTrue();
        assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("4");

        redisTemplate.delete(KEY);
        assertThat(stockStore.restore(PRODUCT_ID, 1)).isFalse();
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    void fortyConcurrentReservationsOnTwentyUnitsNeverOversell() throws Exception {
        assertThat(stockStore.seedIfAbsent(PRODUCT_ID, 20)).isTrue();
        int attempts = 40;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SeckillStockStore.Reservation>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return stockStore.reserve(PRODUCT_ID, 1);
                }));
            }
            start.countDown();

            int reserved = 0;
            int soldOut = 0;
            for (Future<SeckillStockStore.Reservation> future
                    : futures) {
                SeckillStockStore.Reservation reservation = future.get(30, TimeUnit.SECONDS);
                if (reservation.status() == SeckillStockStore.ReservationStatus.RESERVED) {
                    reserved++;
                } else if (reservation.status()
                        == SeckillStockStore.ReservationStatus.SOLD_OUT) {
                    soldOut++;
                }
            }
            assertThat(reserved).isEqualTo(20);
            assertThat(soldOut).isEqualTo(20);
            assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("0");
        } finally {
            pool.shutdownNow();
        }
    }
}
