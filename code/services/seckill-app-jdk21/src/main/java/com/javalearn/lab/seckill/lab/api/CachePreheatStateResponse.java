package com.javalearn.lab.seckill.lab.api;

/**
 * Console-friendly snapshot of the Caffeine preheat mechanism (M4-3, design
 * 8.4). Deliberately small: the enabled flag, the shared hot list, the local
 * tracker size, live run status and the last run's counts. No keys other than
 * the configured hot-list name and no sensitive values are exposed.
 */
public record CachePreheatStateResponse(
        boolean enabled,
        boolean cacheEnabled,
        String hotListKey,
        long topN,
        long hotListSize,
        long trackedKeys,
        boolean running,
        LastRun lastRun) {

    /**
     * @param at ISO-8601 start instant of the run
     * @param outcome success | cache-disabled | busy | failed
     */
    public record LastRun(
            String trigger,
            String at,
            String outcome,
            long durationMs,
            int requested,
            int loaded,
            int skippedFresh,
            int missing,
            int failed,
            String error) {
    }
}
