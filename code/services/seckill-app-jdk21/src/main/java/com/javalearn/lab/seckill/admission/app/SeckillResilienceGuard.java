package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.BusinessException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import com.javalearn.lab.seckill.lab.toggle.LabToggle;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Rate limiting and circuit breaking around the asynchronous seckill admission.
 *
 * <p>Resilience4j registries are resolved and executed manually instead of via
 * annotations: the runtime lab toggles must be able to skip each protection
 * without rebuilding the Spring proxy. The guard is the <em>only</em> place
 * that consults the rate limiter and the circuit breaker, and the synchronous
 * fallback is passed in as a plain supplier, so degradation can never re-enter
 * either protection (no recursion, exactly one permission check per request).</p>
 *
 * <p>Exception taxonomy (Task 17 review fix): {@link BusinessException} is the
 * circuit breaker's ignore list, so expected business outcomes (sold out, lock
 * timeout) are rethrown untouched and never count as failures. A plain
 * {@link BizException} with {@code 50301} is how the admission service wraps
 * real Redis/MQ/stock/publish faults: it is rethrown as-is while the circuit
 * is CLOSED (the HTTP 503 contract from Task 16 stays intact) but IS recorded
 * by the circuit breaker, so a sustained infrastructure outage accumulates the
 * failure rate, opens the circuit and the following calls degrade. Any other
 * runtime failure (or an already-open circuit) degrades immediately to the
 * durable synchronous database path and is counted as {@code degraded}.</p>
 */
public final class SeckillResilienceGuard {

    public static final String RATE_LIMITER_NAME = "seckill";
    public static final String CIRCUIT_BREAKER_NAME = "seckillBackend";

    private static final Logger log = LoggerFactory.getLogger(SeckillResilienceGuard.class);

    private final LabToggleService toggles;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final SeckillMetrics metrics;

    public SeckillResilienceGuard(LabToggleService toggles,
                                  RateLimiter rateLimiter,
                                  CircuitBreaker circuitBreaker,
                                  SeckillMetrics metrics) {
        this.toggles = Objects.requireNonNull(toggles, "toggles");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Runs the asynchronous admission once, under the enabled protections.
     *
     * @param asyncAdmission      the async path (MQ enabled)
     * @param synchronousFallback the durable DB path used when the circuit is
     *                            open or the async path fails unexpectedly
     * @throws BizException {@code 42901} when the rate limiter rejects the call;
     *                      any other {@code BizException} from the async path
     */
    public SeckillResponse admitAsync(Supplier<SeckillResponse> asyncAdmission,
                                      Supplier<SeckillResponse> synchronousFallback) {
        checkRateLimitOnce();

        if (!toggles.isEnabled(LabToggle.CIRCUITBREAKER)) {
            return asyncAdmission.get();
        }

        try {
            return circuitBreaker.executeSupplier(asyncAdmission);
        } catch (CallNotPermittedException notPermitted) {
            return degrade("circuit is open", notPermitted, synchronousFallback);
        } catch (BizException businessFailure) {
            // Ignored by the circuit config; propagate the domain outcome as-is.
            throw businessFailure;
        } catch (RuntimeException failure) {
            return degrade("async admission failed", failure, synchronousFallback);
        }
    }

    private void checkRateLimitOnce() {
        if (!toggles.isEnabled(LabToggle.RATELIMIT)) {
            return;
        }
        if (!rateLimiter.acquirePermission()) {
            metrics.attempt(SeckillMetrics.RESULT_RATE_LIMITED);
            throw new BizException(CommonErrorCode.RATE_LIMITED,
                    "Seckill rate limit exceeded; retry later");
        }
    }

    private SeckillResponse degrade(String reason,
                                    RuntimeException cause,
                                    Supplier<SeckillResponse> synchronousFallback) {
        metrics.attempt(SeckillMetrics.RESULT_DEGRADED);
        log.warn("Seckill admission degraded to the synchronous DB path: {}", reason, cause);
        return synchronousFallback.get();
    }
}
