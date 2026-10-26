package com.javalearn.lab.seckill.admission.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Seckill meters. All result counters are registered eagerly so the label
 * series exist at zero before the first request (dashboards and alerts bind to
 * the label set, not to observed traffic).
 *
 * <p>Prometheus appends {@code _total} to counter names: the raw scrape
 * exports {@code seckill_attempts_total}, {@code order_consumer_processed_total}
 * and {@code stock_oversold_total}. The Java meter names match the task brief.</p>
 */
public final class SeckillMetrics {

    public static final String RESULT_ACCEPTED = "accepted";
    public static final String RESULT_REPLAY = "replay";
    public static final String RESULT_SOLD_OUT = "sold_out";
    public static final String RESULT_LOCK_TIMEOUT = "lock_timeout";
    public static final String RESULT_INVALID = "invalid";
    public static final String RESULT_ERROR = "error";
    /** Rate limiter rejected the request (HTTP 429); Task 17. */
    public static final String RESULT_RATE_LIMITED = "rate_limited";
    /** Async admission failed and the synchronous DB path served the request. */
    public static final String RESULT_DEGRADED = "degraded";

    public static final String CONSUMER_CREATED = "created";
    public static final String CONSUMER_DUPLICATE = "duplicate";
    public static final String CONSUMER_FAILED = "failed";

    private final Map<String, Counter> attempts;
    private final Map<String, Counter> consumerProcessed;
    private final Counter stockOversold;
    private final Timer orderAcceptTimer;

    public SeckillMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.attempts = registerCounters(meterRegistry, "seckill_attempts",
                "Seckill admission attempts by result",
                List.of(RESULT_ACCEPTED, RESULT_REPLAY, RESULT_SOLD_OUT,
                        RESULT_LOCK_TIMEOUT, RESULT_INVALID, RESULT_ERROR,
                        RESULT_RATE_LIMITED, RESULT_DEGRADED));
        this.consumerProcessed = registerCounters(meterRegistry, "order_consumer_processed",
                "Seckill order messages processed by the consumer",
                List.of(CONSUMER_CREATED, CONSUMER_DUPLICATE, CONSUMER_FAILED));
        this.stockOversold = Counter.builder("stock_oversold")
                .description("Oversell assertion metric; must stay zero")
                .register(meterRegistry);
        this.orderAcceptTimer = Timer.builder("order_accept_seconds")
                .description("Async seckill acceptance latency (lock, idempotency, stock, publish)")
                .register(meterRegistry);
    }

    public void attempt(String result) {
        counter(attempts, result).increment();
    }

    public void consumerProcessed(String result) {
        counter(consumerProcessed, result).increment();
    }

    public void recordAcceptNanos(long nanos) {
        orderAcceptTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Exposed for the acceptance test that asserts oversell never happened.
     * Production code must never increment it.
     */
    public Counter stockOversold() {
        return stockOversold;
    }

    private static Map<String, Counter> registerCounters(MeterRegistry meterRegistry,
                                                         String name,
                                                         String description,
                                                         List<String> results) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String result : results) {
            counters.put(result, Counter.builder(name)
                    .description(description)
                    .tag("result", result)
                    .register(meterRegistry));
        }
        return counters;
    }

    private static Counter counter(Map<String, Counter> counters, String result) {
        Counter counter = counters.get(result);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown seckill metric result: " + result);
        }
        return counter;
    }
}
