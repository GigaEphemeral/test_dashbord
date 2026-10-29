package com.javalearn.lab.seckill.admission.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * B4-01 startup configuration of the business executor that runs the seckill
 * order consumer's work.
 *
 * <p>Before B4 the listener threads did the work themselves, so there was no
 * executor to observe and nothing for a core/max/queue/rejection experiment to
 * change. This pool is the controlled, bounded hop between the Rabbit listener
 * and {@code SeckillOrderConsumer#process}; it is the "业务线程池" of design
 * §4.4/§16.5. Every value is a startup parameter: changing one requires the
 * controlled restart of B4-02, which is exactly what the console's
 * "待重启/应用中/已生效" states describe.</p>
 */
@ConfigurationProperties(prefix = "app.business-pool")
public class BusinessPoolProperties {

    /** Spring's default rejection-policy names this pool accepts. */
    public static final List<String> REJECTION_POLICIES = List.of(
            "AbortPolicy", "CallerRunsPolicy", "DiscardPolicy", "DiscardOldestPolicy");

    /** Threads kept alive even when idle. */
    private int corePoolSize;

    /** Upper bound; only reached after the queue is full. */
    private int maxPoolSize;

    /** Bounded waiting queue; a full queue hands the task to the rejection policy. */
    private int queueCapacity;

    /** Keep-alive of threads above the core size. */
    private long keepAliveSeconds;

    /** One of {@link #REJECTION_POLICIES}. */
    private String rejectionPolicy;

    /** Prefix of the executor's thread names (visible in thread dumps). */
    private String threadNamePrefix;

    @PostConstruct
    void validate() {
        if (corePoolSize < 1) {
            throw new IllegalStateException("app.business-pool.core-pool-size must be positive (was "
                    + corePoolSize + "); env BUSINESS_POOL_CORE_SIZE");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalStateException("app.business-pool.max-pool-size (" + maxPoolSize
                    + ") must not be smaller than core-pool-size (" + corePoolSize
                    + "); env BUSINESS_POOL_MAX_SIZE");
        }
        if (queueCapacity < 1) {
            throw new IllegalStateException("app.business-pool.queue-capacity must be positive (was "
                    + queueCapacity + "); env BUSINESS_POOL_QUEUE_CAPACITY");
        }
        if (keepAliveSeconds < 0) {
            throw new IllegalStateException("app.business-pool.keep-alive-seconds must not be negative (was "
                    + keepAliveSeconds + "); env BUSINESS_POOL_KEEP_ALIVE_SECONDS");
        }
        if (rejectionPolicy == null || !REJECTION_POLICIES.contains(rejectionPolicy)) {
            throw new IllegalStateException("app.business-pool.rejection-policy must be one of "
                    + REJECTION_POLICIES + " (was '" + rejectionPolicy
                    + "'); env BUSINESS_POOL_REJECTION_POLICY");
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalStateException("app.business-pool.thread-name-prefix must not be blank"
                    + "; env BUSINESS_POOL_THREAD_NAME_PREFIX");
        }
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(long keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public String getRejectionPolicy() {
        return rejectionPolicy;
    }

    public void setRejectionPolicy(String rejectionPolicy) {
        this.rejectionPolicy = rejectionPolicy;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }
}
