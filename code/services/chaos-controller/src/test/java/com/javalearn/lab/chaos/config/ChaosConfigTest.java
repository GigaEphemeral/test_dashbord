package com.javalearn.lab.chaos.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosConfigTest {

    @Test
    void schedulerThreadCountIsExternalized() throws Exception {
        ChaosProperties properties = new ChaosProperties();
        properties.setSchedulerThreads(7);

        ScheduledExecutorService pool = new ChaosConfig().chaosActionScheduler(properties);
        try {
            assertThat(((ScheduledThreadPoolExecutor) pool).getCorePoolSize()).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void schedulerDefaultsToFourThreadsAndFloorsToOne() throws Exception {
        ChaosConfig config = new ChaosConfig();

        ScheduledExecutorService byDefault = config.chaosActionScheduler(new ChaosProperties());
        assertThat(((ScheduledThreadPoolExecutor) byDefault).getCorePoolSize()).isEqualTo(4);
        byDefault.shutdownNow();

        ChaosProperties broken = new ChaosProperties();
        broken.setSchedulerThreads(0);
        ScheduledExecutorService floored = config.chaosActionScheduler(broken);
        assertThat(((ScheduledThreadPoolExecutor) floored).getCorePoolSize()).isGreaterThanOrEqualTo(1);
        floored.shutdownNow();
    }

    @Test
    void schedulerThreadsAreNamedDaemons() throws Exception {
        ChaosProperties properties = new ChaosProperties();
        properties.setSchedulerThreads(2);
        ScheduledExecutorService pool = new ChaosConfig().chaosActionScheduler(properties);
        try {
            var submit = pool.submit(() -> Thread.currentThread());
            Thread worker = submit.get(5, TimeUnit.SECONDS);
            assertThat(worker.isDaemon()).isTrue();
            assertThat(worker.getName()).startsWith("chaos-action-");
        } finally {
            pool.shutdownNow();
        }
    }
}
