package com.javalearn.lab.seckill.lab.toggle;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Toggle store semantics: seeded defaults, exact six-key snapshot, validated
 * updates and lock-free concurrent writes.
 */
class LabToggleServiceTest {

    @Test
    void defaultsComeFromPropertiesInStableOrder() {
        LabToggleService service = new LabToggleService(properties(
                true, false, true, false, true, false));

        Map<String, Boolean> snapshot = service.snapshot();

        assertThat(snapshot.keySet()).containsExactly(
                "lock", "idempotency", "mq", "ratelimit", "circuitbreaker", "cache");
        assertThat(snapshot).containsEntry("lock", true)
                .containsEntry("idempotency", false)
                .containsEntry("mq", true)
                .containsEntry("ratelimit", false)
                .containsEntry("circuitbreaker", true)
                .containsEntry("cache", false);
        assertThat(service.isEnabled(LabToggle.LOCK)).isTrue();
        assertThat(service.isEnabled(LabToggle.IDEMPOTENCY)).isFalse();
    }

    @Test
    void updateChangesExactlyOneToggle() {
        LabToggleService service = new LabToggleService(properties(
                true, true, true, true, true, true));

        service.update("cache", false);

        assertThat(service.isEnabled(LabToggle.CACHE)).isFalse();
        assertThat(service.snapshot()).containsEntry("cache", false)
                .containsEntry("lock", true)
                .containsEntry("idempotency", true)
                .containsEntry("mq", true)
                .containsEntry("ratelimit", true)
                .containsEntry("circuitbreaker", true);
    }

    @Test
    void unknownKeyIsABadRequest() {
        LabToggleService service = new LabToggleService(properties(
                true, true, true, true, true, true));

        assertThatThrownBy(() -> service.update("circuit", false))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).isEqualTo("Unknown toggle: circuit");
                });
    }

    @Test
    void blankKeyIsABadRequest() {
        LabToggleService service = new LabToggleService(properties(
                true, true, true, true, true, true));

        assertThatThrownBy(() -> service.update("  ", true))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> service.update(null, true))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void invalidValueIsABadRequest() {
        LabToggleService service = new LabToggleService(properties(
                true, true, true, true, true, true));

        assertThatThrownBy(() -> service.update("cache", null))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).isEqualTo(
                            "Toggle value is required for 'cache'");
                });
    }

    @Test
    void concurrentUpdatesNeverCorruptTheSnapshot() throws Exception {
        LabToggleService service = new LabToggleService(properties(
                true, true, true, true, true, true));
        int threads = 8;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threads; thread++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        boolean value = iteration % 2 == 0;
                        service.update("cache", value);
                        service.update("lock", value);
                        assertThat(service.snapshot()).hasSize(6);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        service.update("cache", false);
        service.update("lock", true);
        assertThat(service.isEnabled(LabToggle.CACHE)).isFalse();
        assertThat(service.isEnabled(LabToggle.LOCK)).isTrue();
        assertThat(service.snapshot()).hasSize(6);
    }

    private static LabToggleProperties properties(boolean lock,
                                                  boolean idempotency,
                                                  boolean mq,
                                                  boolean ratelimit,
                                                  boolean circuitbreaker,
                                                  boolean cache) {
        LabToggleProperties properties = new LabToggleProperties();
        properties.setLock(lock);
        properties.setIdempotency(idempotency);
        properties.setMq(mq);
        properties.setRatelimit(ratelimit);
        properties.setCircuitbreaker(circuitbreaker);
        properties.setCache(cache);
        return properties;
    }
}
