package com.javalearn.lab.seckill.lab.cache.preheat;

/**
 * A fully populated {@link LabCachePreheatProperties} for tests. The YAML
 * supplies these values in production; tests that only need a coherent
 * configuration use these defaults and override the one field they care about.
 *
 * <p>Test-scope fixture, not runtime configuration: the numbers are the
 * application.yml defaults, with a deliberately small tracker/top-N so the
 * assertions stay fast and readable.</p>
 */
public final class TestLabCachePreheatProperties {

    public static final long DECAY_SECONDS = 30L;

    private TestLabCachePreheatProperties() {
    }

    public static LabCachePreheatProperties defaults() {
        LabCachePreheatProperties properties = new LabCachePreheatProperties();
        properties.setEnabled(true);
        properties.setTopN(5L);
        properties.setHotListKey("hot:products");

        LabCachePreheatProperties.Tracker tracker = properties.getTracker();
        tracker.setCapacity(100L);
        tracker.setDecayIntervalSeconds(DECAY_SECONDS);
        tracker.setFlushIntervalSeconds(10L);

        LabCachePreheatProperties.Load load = properties.getLoad();
        load.setChunkSize(2L);
        load.setChunkPauseMs(0L);
        load.setStartupDelayMs(0L);
        load.setPeriodicIntervalSeconds(300L);
        load.setSchedulerPoolSize(1L);
        load.setThreadNamePrefix("cache-preheat-test");

        LabCachePreheatProperties.L1 l1 = properties.getL1();
        l1.setMaximumSize(100L);
        l1.setRefreshAfterWriteSeconds(10L);
        l1.setExpireAfterWriteSeconds(60L);
        return properties;
    }
}
