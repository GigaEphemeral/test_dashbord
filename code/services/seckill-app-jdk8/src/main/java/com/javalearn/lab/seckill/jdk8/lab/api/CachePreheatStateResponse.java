package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * Console-friendly snapshot of the Caffeine preheat mechanism (M4-3, design
 * 8.4). Deliberately small: the enabled flag, the shared hot list, the local
 * tracker size, live run status and the last run's counts. No keys other than
 * the configured hot-list name and no sensitive values are exposed.
 */
public final class CachePreheatStateResponse {

    private final boolean enabled;
    private final boolean cacheEnabled;
    private final String hotListKey;
    private final long topN;
    private final long hotListSize;
    private final long trackedKeys;
    private final boolean running;
    private final LastRun lastRun;

    public CachePreheatStateResponse(boolean enabled,
                                     boolean cacheEnabled,
                                     String hotListKey,
                                     long topN,
                                     long hotListSize,
                                     long trackedKeys,
                                     boolean running,
                                     LastRun lastRun) {
        this.enabled = enabled;
        this.cacheEnabled = cacheEnabled;
        this.hotListKey = hotListKey;
        this.topN = topN;
        this.hotListSize = hotListSize;
        this.trackedKeys = trackedKeys;
        this.running = running;
        this.lastRun = lastRun;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public String getHotListKey() {
        return hotListKey;
    }

    public long getTopN() {
        return topN;
    }

    public long getHotListSize() {
        return hotListSize;
    }

    public long getTrackedKeys() {
        return trackedKeys;
    }

    public boolean isRunning() {
        return running;
    }

    public LastRun getLastRun() {
        return lastRun;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    public String hotListKey() {
        return hotListKey;
    }

    public long topN() {
        return topN;
    }

    public long hotListSize() {
        return hotListSize;
    }

    public long trackedKeys() {
        return trackedKeys;
    }

    public boolean running() {
        return running;
    }

    public LastRun lastRun() {
        return lastRun;
    }

    /**
     * @param at ISO-8601 start instant of the run
     * @param outcome success | cache-disabled | busy | failed
     */
    public static final class LastRun {

        private final String trigger;
        private final String at;
        private final String outcome;
        private final long durationMs;
        private final int requested;
        private final int loaded;
        private final int skippedFresh;
        private final int missing;
        private final int failed;
        private final String error;

        public LastRun(String trigger,
                       String at,
                       String outcome,
                       long durationMs,
                       int requested,
                       int loaded,
                       int skippedFresh,
                       int missing,
                       int failed,
                       String error) {
            this.trigger = trigger;
            this.at = at;
            this.outcome = outcome;
            this.durationMs = durationMs;
            this.requested = requested;
            this.loaded = loaded;
            this.skippedFresh = skippedFresh;
            this.missing = missing;
            this.failed = failed;
            this.error = error;
        }

        public String getTrigger() {
            return trigger;
        }

        public String getAt() {
            return at;
        }

        public String getOutcome() {
            return outcome;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public int getRequested() {
            return requested;
        }

        public int getLoaded() {
            return loaded;
        }

        public int getSkippedFresh() {
            return skippedFresh;
        }

        public int getMissing() {
            return missing;
        }

        public int getFailed() {
            return failed;
        }

        public String getError() {
            return error;
        }

        public String trigger() {
            return trigger;
        }

        public String at() {
            return at;
        }

        public String outcome() {
            return outcome;
        }

        public long durationMs() {
            return durationMs;
        }

        public int requested() {
            return requested;
        }

        public int loaded() {
            return loaded;
        }

        public int skippedFresh() {
            return skippedFresh;
        }

        public int missing() {
            return missing;
        }

        public int failed() {
            return failed;
        }

        public String error() {
            return error;
        }
    }
}
