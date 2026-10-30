package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

import com.javalearn.lab.seckill.jdk8.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.jdk8.lab.toggle.LabToggleService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Deterministic test doubles for the preheat lab: a mutable wall clock for the
 * decay math and a fully populated toggle store for the cache manager.
 */
final class PreheatTestSupport {

    private PreheatTestSupport() {
    }

    static MutableClock clock(long epochMillis) {
        return new MutableClock(Instant.ofEpochMilli(epochMillis));
    }

    static LabToggleService toggles(boolean cacheEnabled) {
        LabToggleProperties properties = new LabToggleProperties();
        properties.setLock(true);
        properties.setIdempotency(true);
        properties.setMq(true);
        properties.setRatelimit(true);
        properties.setCircuitbreaker(true);
        properties.setCache(cacheEnabled);
        return new LabToggleService(properties);
    }

    /** Advance-only clock so decay assertions are exact and instant. */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration delta) {
            instant = instant.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
