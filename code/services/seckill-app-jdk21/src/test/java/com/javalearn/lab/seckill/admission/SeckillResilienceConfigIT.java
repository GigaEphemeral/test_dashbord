package com.javalearn.lab.seckill.admission;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.BusinessException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import com.javalearn.lab.seckill.admission.app.SeckillResilienceGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the <em>production</em> {@code resilience4j.*} configuration (bound
 * from {@code application.yml} by the Boot 3 starter in a full Spring context
 * against Testcontainers) treats real infrastructure failures as circuit
 * breaker failures, not as ignored business outcomes.
 *
 * <p>Task 16 wraps every Redis/MQ/stock/publish/state-read fault as
 * {@code BizException(50301)}. If the circuit breaker ignored all
 * {@code BizException}s, the container-kill and Toxiproxy faults of the chaos
 * catalog could never open {@code seckillBackend}, and the D4 circuit-breaker
 * demo would not exist. These tests use the real {@link CircuitBreakerRegistry}
 * and the real {@link SeckillResilienceGuard}; nothing about the breaker
 * mechanics is mocked. The breaker is always {@code reset()} afterwards so the
 * shared context never stays OPEN for another test class.</p>
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=seckill-resilience-config-it",
                "app.seckill.consumer.delay-ms=0",
                "app.seckill.lock.wait-ms=5000",
                "app.seckill.mq.exchange=seckill.order.exchange.resilience-it",
                "app.seckill.mq.queue=seckill.order.queue.resilience-it",
                "app.seckill.mq.routing-key=seckill.order.requested.resilience-it"
        })
class SeckillResilienceConfigIT extends SeckillIntegrationTestSupport {

    private static final SeckillResponse SYNCHRONOUS_CREATED =
            new SeckillResponse(null, "CREATED", 7L);

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private SeckillResilienceGuard resilienceGuard;

    @AfterEach
    void resetCircuitBreaker() {
        productionCircuitBreaker().reset();
    }

    @Test
    void infrastructureWrappedFailuresOpenTheCircuitWithTheProductionConfig() {
        CircuitBreaker circuitBreaker = productionCircuitBreaker();
        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // The exact exception the production admission path throws when
        // Redis/MQ break (SeckillAdmissionService wraps faults as 50301).
        Supplier<SeckillResponse> infrastructureFailure = () -> {
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill stock is unavailable");
        };

        // While the circuit is CLOSED the 503 contract is preserved: every
        // wrapped infrastructure failure propagates to the caller.
        for (int call = 0; call < 5; call++) {
            assertThatThrownBy(() -> resilienceGuard.admitAsync(infrastructureFailure,
                    () -> SYNCHRONOUS_CREATED))
                    .isInstanceOfSatisfying(BizException.class, exception ->
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(5L);

        // With the production circuit open the next call degrades to the
        // synchronous DB fallback: CREATED with an orderId, no requestId.
        SeckillResponse degraded = resilienceGuard.admitAsync(infrastructureFailure,
                () -> SYNCHRONOUS_CREATED);
        assertThat(degraded.status()).isEqualTo("CREATED");
        assertThat(degraded.orderId()).isEqualTo(7L);
        assertThat(degraded.requestId()).isNull();
        assertThat(attempts("degraded")).isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    void expectedBusinessFailuresNeverOpenTheProductionCircuit() {
        CircuitBreaker circuitBreaker = productionCircuitBreaker();
        circuitBreaker.reset();

        // Sold out / lock timeout are BusinessException: rethrown to the client
        // (409 contract) but ignored by the breaker, far beyond the minimum
        // number of calls and the failure-rate window.
        Supplier<SeckillResponse> soldOut = () -> {
            throw new BusinessException(CommonErrorCode.CONFLICT, "Sold out");
        };
        double degradedBefore = attempts("degraded");
        for (int call = 0; call < 8; call++) {
            assertThatThrownBy(() -> resilienceGuard.admitAsync(soldOut,
                    () -> SYNCHRONOUS_CREATED))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(attempts("degraded")).isEqualTo(degradedBefore);
    }

    @Test
    void unexpectedRuntimeExceptionOpensTheProductionCircuitAndDegrades() {
        CircuitBreaker circuitBreaker = productionCircuitBreaker();
        circuitBreaker.reset();

        // Non-BizException failures keep the Task 17 behaviour: recorded by
        // the breaker (openCircuitFallsBackToSynchronousCreated contract).
        Supplier<SeckillResponse> unexpected = () -> {
            throw new IllegalStateException("async backend exploded");
        };
        for (int call = 0; call < 5; call++) {
            assertThat(resilienceGuard.admitAsync(unexpected, () -> SYNCHRONOUS_CREATED))
                    .isEqualTo(SYNCHRONOUS_CREATED);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        SeckillResponse degraded = resilienceGuard.admitAsync(unexpected,
                () -> SYNCHRONOUS_CREATED);
        assertThat(degraded.status()).isEqualTo("CREATED");
        assertThat(degraded.requestId()).isNull();
    }

    private CircuitBreaker productionCircuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(
                SeckillResilienceGuard.CIRCUIT_BREAKER_NAME);
    }
}
