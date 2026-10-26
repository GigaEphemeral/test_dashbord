package com.javalearn.lab.seckill.lab.thread;

/**
 * A fully populated {@link LabThreadProperties} for tests, mirroring
 * {@code TestLabCacheProperties}: the numbers are application.yml defaults shrunk
 * so the created threads drain instantly and the tests stay fast. Tests override
 * the single knob they care about.
 */
final class TestLabThreadProperties {

    static final long DEFAULT_TTL_SECONDS = 120L;

    private TestLabThreadProperties() {
    }

    static LabThreadProperties defaults() {
        LabThreadProperties properties = new LabThreadProperties();
        properties.setDefaultTtlSeconds(DEFAULT_TTL_SECONDS);
        properties.setShutdownJoinTimeoutMs(500L);

        LabThreadProperties.Deadlock deadlock = properties.getDeadlock();
        deadlock.setThreadCount(2L);
        deadlock.setHoldMs(10L);

        LabThreadProperties.Stuck stuck = properties.getStuck();
        stuck.setThreadCount(3L);
        stuck.setPollMs(20L);

        LabThreadProperties.CpuBurn cpuBurn = properties.getCpuBurn();
        cpuBurn.setThreadCount(1L);
        cpuBurn.setYieldEveryLoops(1000L);

        LabThreadProperties.HeapLeak heapLeak = properties.getHeapLeak();
        heapLeak.setChunkBytes(1024L);
        heapLeak.setIntervalMs(5L);
        heapLeak.setCapPercent(80L);

        LabThreadProperties.Oom oom = properties.getOom();
        oom.setThrowawayInstance(false);
        oom.setChunkBytes(1024L);
        oom.setIntervalMs(5L);

        LabThreadProperties.ThreadLeak threadLeak = properties.getThreadLeak();
        threadLeak.setMaxLiveThreads(30L);
        threadLeak.setSpawnCount(3L);
        threadLeak.setSpawnIntervalMs(5L);
        threadLeak.setHoldMs(100_000L);
        threadLeak.setVirtual(false);

        LabThreadProperties.Starvation starvation = properties.getStarvation();
        starvation.setPoolSize(2L);
        starvation.setQueueCapacity(5L);
        starvation.setSubmitCount(10L);
        starvation.setTaskBlockMs(100_000L);

        return properties;
    }
}
