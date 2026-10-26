package com.javalearn.lab.seckill.lab.cache.preheat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the M4-3 background machinery only after the application is ready
 * (design 8.4): the hot-list flush always runs; the startup warm-up and the
 * periodic incremental reheat run only when {@code app.lab.cache.preheat.enabled}.
 *
 * <p>Nothing here blocks startup: {@link ApplicationReadyEvent} is already
 * async, every task is submitted to the daemon scheduler owned by
 * {@code CachePreheatConfig}, and a task failure is caught and logged so it can
 * never cancel a fixed-rate schedule. Keeping this class free of a directly
 * created executor also makes the enabled/disabled gating unit-testable with a
 * fake scheduler.</p>
 */
public final class CachePreheatLifecycle implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(CachePreheatLifecycle.class);

    private final LabCachePreheatProperties properties;
    private final CachePreheatService preheatService;
    private final HotKeyTracker tracker;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();

    public CachePreheatLifecycle(LabCachePreheatProperties properties,
                                 CachePreheatService preheatService,
                                 HotKeyTracker tracker,
                                 ScheduledExecutorService scheduler) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.preheatService = Objects.requireNonNull(preheatService, "preheatService");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /** Idempotent; exposed for tests that do not publish a full Boot event. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long flushSeconds = properties.getTracker().getFlushIntervalSeconds();
        scheduler.scheduleAtFixedRate(guarded("hot-list flush", tracker::publishTopN),
                flushSeconds, flushSeconds, TimeUnit.SECONDS);

        if (!properties.isEnabled()) {
            log.info("Caffeine preheat disabled (app.lab.cache.preheat.enabled=false);"
                    + " hot-list tracking/publishing stays active");
            return;
        }
        long startupDelayMs = properties.getLoad().getStartupDelayMs();
        scheduler.schedule(guarded("startup preheat",
                        () -> preheatService.run(PreheatTrigger.STARTUP)),
                startupDelayMs, TimeUnit.MILLISECONDS);

        long periodicSeconds = properties.getLoad().getPeriodicIntervalSeconds();
        scheduler.scheduleAtFixedRate(guarded("periodic preheat",
                        () -> preheatService.run(PreheatTrigger.PERIODIC)),
                periodicSeconds, periodicSeconds, TimeUnit.SECONDS);
        log.info("Caffeine preheat scheduled: startup in {}ms, incremental every {}s, Top-N={}",
                startupDelayMs, periodicSeconds, properties.getTopN());
    }

    private static Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                // A fixed-rate task dies on any escaped exception; the lab must
                // keep re-heating after one bad round.
                log.warn("Caffeine preheat task '{}' failed: {}", name, ex.toString());
            }
        };
    }
}
