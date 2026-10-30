package com.javalearn.lab.seckill.jdk8.admission.config;

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
 * group applies by recreating the container and reads the effective values back
 * through {@code GET /lab/pool} on the JDK 21 peers. Two Micrometer bindings
 * make the executor observable from Prometheus without any dashboard-side
 * computation: {@link ExecutorServiceMetrics} exports
 * {@code executor_active_threads}, {@code executor_queued_tasks},
 * {@code executor_pool_size}, {@code executor_completed_tasks} (all tagged
 * {@code name="business"}), and this class adds
 * {@code executor_rejected_total}, the one series Micrometer's own binder
 * cannot provide (the {@code ThreadPoolExecutor} API exposes no rejection
 * count).</p>
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
                new ArrayBlockingQueue<Runnable>(properties.getQueueCapacity()),
                threadFactory(properties.getThreadNamePrefix()),
                rejectionPolicy(properties.getRejectionPolicy(), rejected));
        // ExecutorServiceMetrics only unwraps ThreadPoolExecutor-like types; the
        // executor stays a ThreadPoolExecutor bean so the readback endpoint can
        // report core/max/queue directly.
        ExecutorServiceMetrics.monitor(meterRegistry, executor, EXECUTOR_NAME);
        return executor;
    }

    private static ThreadFactory threadFactory(final String prefix) {
        final AtomicInteger sequence = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /**
     * Wraps one of Spring's {@link ThreadPoolExecutor} rejection policies so a
     * refusal is counted before it is delegated. The counter is the only state
     * this class adds; the policy semantics stay the JDK's.
     */
    static RejectedExecutionHandler rejectionPolicy(String name, final Counter rejected) {
        final RejectedExecutionHandler delegate = jdkPolicy(name);
        return new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
                rejected.increment();
                delegate.rejectedExecution(runnable, executor);
            }
        };
    }

    /**
     * The unwrapped JDK policy for one configured name. Kept separate so tests
     * can assert the exact mapping without reaching into the counting wrapper.
     */
    static RejectedExecutionHandler jdkPolicy(String name) {
        if ("AbortPolicy".equals(name)) {
            return new ThreadPoolExecutor.AbortPolicy();
        }
        if ("CallerRunsPolicy".equals(name)) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        if ("DiscardPolicy".equals(name)) {
            return new ThreadPoolExecutor.DiscardPolicy();
        }
        if ("DiscardOldestPolicy".equals(name)) {
            return new ThreadPoolExecutor.DiscardOldestPolicy();
        }
        // BusinessPoolProperties#validate already fails startup; keep a
        // defensive throw for direct construction in tests.
        throw new IllegalArgumentException("Unsupported rejection policy: " + name);
    }
}
