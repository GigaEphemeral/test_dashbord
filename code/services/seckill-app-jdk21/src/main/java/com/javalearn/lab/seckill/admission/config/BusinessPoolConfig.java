package com.javalearn.lab.seckill.admission.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bounded business executor of design §4.4/§16.5 (B4-01).
 *
 * <p>Everything about it is a startup parameter, so the console's configuration
 * group applies by recreating the container (B4-02) and reads the effective
 * values back through {@code GET /lab/pool}. Two Micrometer bindings make the
 * executor observable from Prometheus without any dashboard-side computation:
 * {@link ExecutorServiceMetrics} exports {@code executor_active_threads},
 * {@code executor_queued_tasks}, {@code executor_pool_size},
 * {@code executor_completed_tasks} (all tagged {@code name="business"}), and
 * this class adds {@code executor_rejected_total}, the one series Micrometer's
 * own binder cannot provide (the {@code ThreadPoolExecutor} API exposes no
 * rejection count). The catalog key {@code executor.rejected} pointed at
 * exactly this export name since B1-03.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessPoolProperties.class)
public class BusinessPoolConfig {

    static final String EXECUTOR_NAME = "business";

    @Bean(name = "businessExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor businessExecutor(BusinessPoolProperties properties,
                                               MeterRegistry meterRegistry) {
        Counter rejected = Counter.builder("executor.rejected")
                .description("Tasks refused by the business executor's rejection policy")
                .tag("name", EXECUTOR_NAME)
                .register(meterRegistry);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                threadFactory(properties.getThreadNamePrefix()),
                rejectionPolicy(properties.getRejectionPolicy(), rejected));
        // ExecutorServiceMetrics only unwraps ThreadPoolExecutor-like types; the
        // executor stays a ThreadPoolExecutor bean so the readback endpoint can
        // report core/max/queue directly.
        ExecutorServiceMetrics.monitor(meterRegistry, executor, EXECUTOR_NAME);
        return executor;
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Wraps one of Spring's {@link ThreadPoolExecutor} rejection policies so a
     * refusal is counted before it is delegated. The counter is the only state
     * this class adds; the policy semantics stay the JDK's.
     */
    static RejectedExecutionHandler rejectionPolicy(String name, Counter rejected) {
        RejectedExecutionHandler delegate = jdkPolicy(name);
        return (runnable, executor) -> {
            rejected.increment();
            delegate.rejectedExecution(runnable, executor);
        };
    }

    /**
     * The unwrapped JDK policy for one configured name. Kept separate so tests
     * can assert the exact mapping without reaching into the counting wrapper.
     */
    static RejectedExecutionHandler jdkPolicy(String name) {
        return switch (name) {
            case "AbortPolicy" -> new ThreadPoolExecutor.AbortPolicy();
            case "CallerRunsPolicy" -> new ThreadPoolExecutor.CallerRunsPolicy();
            case "DiscardPolicy" -> new ThreadPoolExecutor.DiscardPolicy();
            case "DiscardOldestPolicy" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            // BusinessPoolProperties#validate already fails startup; keep a
            // defensive throw for direct construction in tests.
            default -> throw new IllegalArgumentException("Unsupported rejection policy: " + name);
        };
    }
}
