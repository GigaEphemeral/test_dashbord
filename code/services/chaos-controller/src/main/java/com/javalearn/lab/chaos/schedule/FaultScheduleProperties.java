package com.javalearn.lab.chaos.schedule;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * B3-00b run-scoped fault timeline configuration.
 *
 * <p>Every value is externalised in {@code application.yml}, mirrored in
 * {@code compose.base.yml} and {@code .env.example} with byte-identical
 * defaults. The scheduler is a dedicated daemon pool, deliberately separate
 * from both the chaos TTL/revert scheduler and the ledger operation pool.</p>
 */
@ConfigurationProperties(prefix = "lab.fault-schedule")
public class FaultScheduleProperties {

    /** Largest accepted {@code atSeconds}; keeps a typo from scheduling days ahead. */
    private int maxOffsetSeconds = 3600;

    /** Thread-name prefix of the dedicated daemon scheduler. */
    private String threadName = "fault-schedule";

    /** Daemon threads used to fire due schedule entries. */
    private int poolSize = 2;

    @PostConstruct
    public void validate() {
        if (maxOffsetSeconds < 0 || maxOffsetSeconds > 86_400) {
            throw new IllegalStateException("lab.fault-schedule.max-offset-seconds must be between 0 "
                    + "and 86400 (was " + maxOffsetSeconds
                    + "); env LAB_FAULT_SCHEDULE_MAX_OFFSET_SECONDS");
        }
        if (threadName == null || threadName.isBlank()) {
            throw new IllegalStateException("lab.fault-schedule.thread-name must not be blank; "
                    + "env LAB_FAULT_SCHEDULE_THREAD_NAME");
        }
        if (poolSize < 1 || poolSize > 32) {
            throw new IllegalStateException("lab.fault-schedule.pool-size must be between 1 and 32 (was "
                    + poolSize + "); env LAB_FAULT_SCHEDULE_POOL_SIZE");
        }
    }

    public int getMaxOffsetSeconds() {
        return maxOffsetSeconds;
    }

    public void setMaxOffsetSeconds(int maxOffsetSeconds) {
        this.maxOffsetSeconds = maxOffsetSeconds;
    }

    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
}
