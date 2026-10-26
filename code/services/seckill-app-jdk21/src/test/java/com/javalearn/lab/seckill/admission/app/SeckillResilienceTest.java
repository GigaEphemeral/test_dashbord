package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Rate limiter and circuit breaker semantics with real Resilience4j registries
 * (and mocks only where a specific state is hard to force, e.g. an already-open
 * circuit on the first call). No sleeps: the limiter uses a one-hour refresh
 * window and the breaker a one-minute open state.
 */
class SeckillResilienceTest {

    private static final SeckillResponse ACCEPTED = new SeckillResponse("request-1", "ACCEPTED", null);
    private static final SeckillResponse CREATED = new SeckillResponse(null, "CREATED", 7L);

    @Test
    void rateLimiterRejectsTheSecondRequestInTheWindow() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SeckillMetrics metrics = new SeckillMetrics(meterRegistry);
        LabToggleService toggles = toggles(true, true);
        RateLimiter rateLimiter = RateLimiter.of("seckill-test",
                RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofHours(1))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        SeckillResilienceGuard guard = new SeckillResilienceGuard(
                toggles, rateLimiter, closedBreaker(), metrics);
        AtomicInteger asyncCalls = new AtomicInteger();

        SeckillResponse first = guard.admitAsync(() -> {
            asyncCalls.incrementAndGet();
            return ACCEPTED;
        }, () -> CREATED);

        assertThat(first).isEqualTo(ACCEPTED);
        assertThatThrownBy(() -> guard.admitAsync(() -> {
            asyncCalls.incrementAndGet();
            return ACCEPTED;
        }, () -> CREATED))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RATE_LIMITED);
                    assertThat(exception.getMessage()).contains("rate limit");
                });

        assertThat(asyncCalls).hasValue(1);
        assertThat(attempts(meterRegistry, "rate_limited")).isEqualTo(1.0d);
        assertThat(attempts(meterRegistry, "accepted")).isZero();
    }

    @Test
    void openCircuitFallsBackToSynchronousCreated() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SeckillMetrics metrics = new SeckillMetrics(meterRegistry);
        LabToggleService toggles = toggles(true, true);
        CircuitBreaker circuitBreaker = CircuitBreaker.of("seckillBackend-test",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .ignoreExceptions(BizException.class)
                        .build());
        SeckillResilienceGuard guard = new SeckillResilienceGuard(
                toggles, permissiveLimiter(), circuitBreaker, metrics);
        AtomicInteger asyncCalls = new AtomicInteger();
        Supplier<SeckillResponse> failingAsync = () -> {
            asyncCalls.incrementAndGet();
            throw new IllegalStateException("async backend is down");
        };

        assertThat(guard.admitAsync(failingAsync, () -> CREATED)).isEqualTo(CREATED);
        assertThat(guard.admitAsync(failingAsync, () -> CREATED)).isEqualTo(CREATED);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(guard.admitAsync(failingAsync, () -> CREATED)).isEqualTo(CREATED);

        assertThat(asyncCalls).hasValue(2);
        assertThat(attempts(meterRegistry, "degraded")).isEqualTo(3.0d);
    }

    @Test
    void bizExceptionIsIgnoredAndDoesNotOpenTheCircuit() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SeckillMetrics metrics = new SeckillMetrics(meterRegistry);
        LabToggleService toggles = toggles(true, true);
        CircuitBreaker circuitBreaker = CircuitBreaker.of("seckillBackend-test",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .ignoreExceptions(BizException.class)
                        .build());
        SeckillResilienceGuard guard = new SeckillResilienceGuard(
                toggles, permissiveLimiter(), circuitBreaker, metrics);
        Supplier<SeckillResponse> soldOut = () -> {
            throw new BizException(CommonErrorCode.CONFLICT, "Sold out");
        };

        for (int call = 0; call < 5; call++) {
            assertThatThrownBy(() -> guard.admitAsync(soldOut, () -> CREATED))
                    .isInstanceOfSatisfying(BizException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(attempts(meterRegistry, "degraded")).isZero();
    }

    @Test
    void circuitBreakerToggleOffLetsTheAsyncFailurePropagate() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SeckillMetrics metrics = new SeckillMetrics(meterRegistry);
        LabToggleService toggles = toggles(true, false);
        CircuitBreaker circuitBreaker = closedBreaker();
        SeckillResilienceGuard guard = new SeckillResilienceGuard(
                toggles, permissiveLimiter(), circuitBreaker, metrics);

        assertThatThrownBy(() -> guard.admitAsync(
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> CREATED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(attempts(meterRegistry, "degraded")).isZero();
    }

    @Test
    void rateLimiterToggleOffAllowsEveryRequest() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SeckillMetrics metrics = new SeckillMetrics(meterRegistry);
        LabToggleService toggles = toggles(false, true);
        RateLimiter rateLimiter = RateLimiter.of("seckill-test",
                RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofHours(1))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        SeckillResilienceGuard guard = new SeckillResilienceGuard(
                toggles, rateLimiter, closedBreaker(), metrics);

        assertThat(guard.admitAsync(() -> ACCEPTED, () -> CREATED)).isEqualTo(ACCEPTED);
        assertThat(guard.admitAsync(() -> ACCEPTED, () -> CREATED)).isEqualTo(ACCEPTED);

        assertThat(attempts(meterRegistry, "rate_limited")).isZero();
    }

    @Test
    void degradedCallChecksTheRateLimiterExactlyOnceAndSkipsTheAsyncPath() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SeckillMetrics metrics = new SeckillMetrics(meterRegistry);
        LabToggleService toggles = toggles(true, true);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.acquirePermission()).thenReturn(true);
        CircuitBreaker openBreaker = CircuitBreaker.of("open-breaker-test",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(1)
                        .minimumNumberOfCalls(1)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .build());
        openBreaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("force open"));
        assertThat(openBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        SeckillResilienceGuard guard = new SeckillResilienceGuard(
                toggles, rateLimiter, openBreaker, metrics);
        AtomicInteger asyncCalls = new AtomicInteger();

        SeckillResponse response = guard.admitAsync(() -> {
            asyncCalls.incrementAndGet();
            return ACCEPTED;
        }, () -> CREATED);

        assertThat(response).isEqualTo(CREATED);
        assertThat(asyncCalls).hasValue(0);
        verify(rateLimiter, times(1)).acquirePermission();
        verifyNoMoreInteractions(rateLimiter);
        assertThat(attempts(meterRegistry, "degraded")).isEqualTo(1.0d);
    }

    private static LabToggleService toggles(boolean ratelimit, boolean circuitbreaker) {
        LabToggleProperties properties = new LabToggleProperties();
        properties.setLock(true);
        properties.setIdempotency(true);
        properties.setMq(true);
        properties.setRatelimit(ratelimit);
        properties.setCircuitbreaker(circuitbreaker);
        properties.setCache(true);
        return new LabToggleService(properties);
    }

    private static RateLimiter permissiveLimiter() {
        return RateLimiter.of("seckill-test",
                RateLimiterConfig.custom()
                        .limitForPeriod(1000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build());
    }

    private static CircuitBreaker closedBreaker() {
        return CircuitBreaker.of("seckillBackend-test",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .ignoreExceptions(BizException.class)
                        .build());
    }

    private static double attempts(SimpleMeterRegistry registry, String result) {
        Counter counter = registry.find("seckill_attempts").tag("result", result).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
