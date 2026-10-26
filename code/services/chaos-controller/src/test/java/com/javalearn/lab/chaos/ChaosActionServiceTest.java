package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChaosActionServiceTest {

    private static final String APP = "jl-app-3";
    private static final String APP2 = "jl-app-2";

    private ScheduledExecutorService scheduler;
    private FakeGateway gateway;
    private RecordingAnnotator annotator;
    private SimpleMeterRegistry registry;
    private ChaosProperties properties;
    private ChaosActionService service;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(4);
        gateway = new FakeGateway();
        annotator = new RecordingAnnotator();
        registry = new SimpleMeterRegistry();
        properties = new ChaosProperties();
        properties.setDefaultTtlSeconds(1);
        properties.setMaxTtlSeconds(5);
        properties.setActionTimeoutSeconds(1);
        properties.setWatchdogIntervalSeconds(1);
        properties.setStopGraceSeconds(1);
        properties.setMaxRevertAttempts(2);
        service = newService();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private ChaosActionService newService() {
        return new ChaosActionService(gateway, annotator, properties, registry, scheduler);
    }

    @Test
    void killStopsContainerAndRevertsWithStartAfterTtl() {
        ChaosActionView view = service.execute(APP, new ChaosActionRequest("kill", 1));

        assertThat(view.action()).isEqualTo("kill");
        assertThat(view.revertAction()).isEqualTo("start");
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.ttlSeconds()).isEqualTo(1);
        assertThat(view.expiresAt()).isNotNull();
        assertThat(gateway.calls).containsExactly("stop:" + APP);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(gateway.calls).contains("start:" + APP));
        assertThat(gateway.calls).filteredOn(call -> call.equals("start:" + APP)).hasSize(1);
        assertThat(service.activeActions()).isEmpty();
        assertThat(counter("chaos.actions", "action", "kill", "target", APP, "result", "accepted"))
                .isEqualTo(1.0);
        assertThat(counter("chaos.reverts", "action", "kill", "result", "success")).isEqualTo(1.0);
    }

    @Test
    void freezeSendsSigstopAndRevertsWithSigcontAfterTtl() {
        service.execute(APP, new ChaosActionRequest("freeze", 1));

        assertThat(gateway.calls).contains("signal:" + APP + ":SIGSTOP");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(gateway.calls).contains("signal:" + APP + ":SIGCONT"));
        assertThat(service.activeActions()).isEmpty();
        assertThat(counter("chaos.reverts", "action", "freeze", "result", "success")).isEqualTo(1.0);
    }

    @Test
    void activeGaugeTracksActionsUntilRevert() {
        service.execute(APP, new ChaosActionRequest("kill", 5));

        assertThat(registry.get("chaos.active.actions").gauge().value()).isEqualTo(1.0);
        assertThat(service.activeActions()).hasSize(1);

        properties.setMaxRevertAttempts(1);
        StopAllResult result = service.stopAll();

        assertThat(result.reverted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(registry.get("chaos.active.actions").gauge().value()).isZero();
    }

    @Test
    void duplicateRevertingActionOnSameTargetIsRejectedWithConflict() {
        service.execute(APP, new ChaosActionRequest("kill", 5));

        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest("freeze", 5)))
                .isInstanceOf(ChaosConflictException.class)
                .hasMessageContaining(APP);
        assertThat(service.activeActions()).hasSize(1);
        assertThat(gateway.calls).containsExactly("stop:" + APP);
    }

    @Test
    void nonLabContainerIsRejectedBeforeAnyDockerCall() {
        assertThatThrownBy(() -> service.execute("redis", new ChaosActionRequest("kill", 1)))
                .isInstanceOf(ContainerNotAllowedException.class);
        assertThat(gateway.calls).isEmpty();
        assertThat(counter("chaos.actions", "action", "kill", "target", "redis", "result", "rejected"))
                .isEqualTo(1.0);
    }

    @Test
    void unknownContainerReturnsNotFound() {
        assertThatThrownBy(() -> service.execute("jl-missing", new ChaosActionRequest("kill", 1)))
                .isInstanceOf(ContainerNotFoundException.class);
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void ttlIsValidatedAgainstConfiguration() {
        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest("kill", 0)))
                .isInstanceOf(InvalidChaosActionException.class)
                .hasMessageContaining("between 1 and 5");
        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest("kill", 6)))
                .isInstanceOf(InvalidChaosActionException.class);
        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest("explode", null)))
                .isInstanceOf(InvalidChaosActionException.class);
        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest(null, null)))
                .isInstanceOf(InvalidChaosActionException.class);
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void defaultTtlIsUsedWhenOmitted() {
        ChaosActionView view = service.execute(APP, new ChaosActionRequest("kill", null));
        assertThat(view.ttlSeconds()).isEqualTo(1);
    }

    @Test
    void oneShotActionsAreNotTracked() {
        ChaosActionView start = service.execute(APP, new ChaosActionRequest("start", 300));
        ChaosActionView restart = service.execute(APP2, new ChaosActionRequest("restart", null));
        ChaosActionView resume = service.execute(APP, new ChaosActionRequest("resume", null));

        assertThat(start.status()).isEqualTo("completed");
        assertThat(start.ttlSeconds()).isNull();
        assertThat(start.revertAction()).isNull();
        assertThat(restart.status()).isEqualTo("completed");
        assertThat(resume.status()).isEqualTo("completed");
        assertThat(service.activeActions()).isEmpty();
        assertThat(gateway.calls).containsExactly(
                "start:" + APP, "restart:" + APP2, "signal:" + APP + ":SIGCONT");
    }

    @Test
    void stopAllRevertsEveryTrackedActionAndClearsThem() {
        service.execute(APP, new ChaosActionRequest("kill", 5));
        service.execute(APP2, new ChaosActionRequest("freeze", 5));

        StopAllResult result = service.stopAll();

        assertThat(result.reverted()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(service.activeActions()).isEmpty();
        assertThat(gateway.calls).contains("start:" + APP, "signal:" + APP2 + ":SIGCONT");
        assertThat(counter("chaos.reverts", "action", "kill", "result", "success")).isEqualTo(1.0);
        assertThat(counter("chaos.reverts", "action", "freeze", "result", "success")).isEqualTo(1.0);
    }

    @Test
    void revertFailureStaysVisibleAsFailedAndIsCounted() {
        gateway.failStart = true;
        service.execute(APP, new ChaosActionRequest("kill", 1));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(service.activeActions()).anyMatch(action -> "failed".equals(action.status())));
        // The failure reason must be visible through the API, not only in logs.
        assertThat(service.activeActions()).allSatisfy(action -> {
            if ("failed".equals(action.status())) {
                assertThat(action.lastError()).contains("IllegalStateException: docker start failed");
            }
        });
        assertThat(counter("chaos.reverts", "action", "kill", "result", "failed"))
                .isGreaterThanOrEqualTo(1.0);

        StopAllResult result = service.stopAll();
        assertThat(result.reverted()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(service.activeActions()).hasSize(1);
        assertThat(service.activeActions().get(0).status()).isEqualTo("failed");
    }

    @Test
    void failedActionCanBeReplacedByANewAction() {
        gateway.failStart = true;
        service.execute(APP, new ChaosActionRequest("kill", 1));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(service.activeActions()).anyMatch(action -> "failed".equals(action.status())));

        gateway.failStart = false;
        ChaosActionView retry = service.execute(APP, new ChaosActionRequest("kill", 1));

        assertThat(retry.status()).isEqualTo("active");
        assertThat(service.activeActions()).hasSize(1);
        assertThat(service.activeActions().get(0).id()).isEqualTo(retry.id());
    }

    @Test
    void annotationFailureNeverFailsTheAction() {
        annotator.failing.set(true);

        ChaosActionView view = service.execute(APP, new ChaosActionRequest("kill", 1));

        assertThat(view.status()).isEqualTo("active");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(gateway.calls).contains("start:" + APP));
        assertThat(annotator.events).isNotEmpty();
    }

    @Test
    void annotationsCarryLabTagsAndRevertInformation() {
        service.execute(APP, new ChaosActionRequest("freeze", 1));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(annotator.events).hasSize(2));

        AnnotationEvent first = annotator.events.get(0);
        assertThat(first.tags).contains("chaos", "java-learn", APP, "freeze");
        assertThat(first.text).contains("ttl=1s", "revert=resume");
        assertThat(annotator.events.get(1).text).contains("reverted");
    }

    @Test
    void pauseFreezesTtlAndResumeSchedulesOnlyTheRemainingTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        List<Long> delays = new CopyOnWriteArrayList<>();
        List<Runnable> watchdogs = new CopyOnWriteArrayList<>();
        ChaosActionService frozen = serviceWithManualScheduler(clock, tasks, delays, watchdogs);

        frozen.execute(APP, new ChaosActionRequest("kill", 30));
        assertThat(delays).containsExactly(TimeUnit.SECONDS.toMillis(30));

        clock.advance(Duration.ofSeconds(10));
        assertThat(frozen.pause()).isTrue();
        assertThat(frozen.isPaused()).isTrue();

        // A TTL task already in flight must not revert while paused.
        tasks.get(0).run();
        assertThat(gateway.calls).doesNotContain("start:" + APP);

        clock.advance(Duration.ofSeconds(60));
        watchdogs.get(0).run();
        assertThat(gateway.calls).doesNotContain("start:" + APP);
        assertThat(frozen.activeActions()).hasSize(1);

        Instant resumedAt = clock.instant();
        assertThat(frozen.resume()).isTrue();
        assertThat(frozen.isPaused()).isFalse();
        assertThat(delays).containsExactly(TimeUnit.SECONDS.toMillis(30),
                TimeUnit.SECONDS.toMillis(20));
        assertThat(frozen.activeActions().get(0).expiresAt())
                .isEqualTo(resumedAt.plusSeconds(20));

        tasks.get(1).run();
        assertThat(gateway.calls).contains("start:" + APP);
        assertThat(frozen.activeActions()).isEmpty();
    }

    @Test
    void actionCreatedWhilePausedIsFrozenUntilResume() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        List<Long> delays = new CopyOnWriteArrayList<>();
        List<Runnable> watchdogs = new CopyOnWriteArrayList<>();
        ChaosActionService frozen = serviceWithManualScheduler(clock, tasks, delays, watchdogs);

        assertThat(frozen.pause()).isTrue();
        frozen.execute(APP2, new ChaosActionRequest("freeze", 10));

        assertThat(delays).isEmpty();
        assertThat(gateway.calls).contains("signal:" + APP2 + ":SIGSTOP");
        assertThat(frozen.activeActions()).hasSize(1);

        frozen.resume();
        assertThat(delays).containsExactly(TimeUnit.SECONDS.toMillis(10));
        tasks.get(0).run();
        assertThat(gateway.calls).contains("signal:" + APP2 + ":SIGCONT");
        assertThat(frozen.activeActions()).isEmpty();
    }

    @Test
    void stopAllRevertsAndClearsWhilePaused() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        List<Long> delays = new CopyOnWriteArrayList<>();
        List<Runnable> watchdogs = new CopyOnWriteArrayList<>();
        ChaosActionService frozen = serviceWithManualScheduler(clock, tasks, delays, watchdogs);

        frozen.execute(APP, new ChaosActionRequest("kill", 30));
        frozen.pause();

        StopAllResult result = frozen.stopAll();

        assertThat(result.reverted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(gateway.calls).contains("start:" + APP);
        assertThat(frozen.activeActions()).isEmpty();
        // stop-all does not silently unfreeze; the guide reset does that explicitly.
        assertThat(frozen.isPaused()).isTrue();
    }

    @Test
    void resumeIsANoOpWhenNotPaused() {
        assertThat(service.resume()).isFalse();
        assertThat(service.pause()).isTrue();
        assertThat(service.pause()).isFalse();
    }

    @Test
    void pauseLandingDuringScheduleLeavesNoFullTtlTaskBehind() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        properties.setDefaultTtlSeconds(30);
        properties.setMaxTtlSeconds(600);
        properties.setWatchdogIntervalSeconds(60);
        List<CapturedTask> tasks = new CopyOnWriteArrayList<>();
        AtomicBoolean pauseHookArmed = new AtomicBoolean(true);
        ChaosActionService frozen = serviceWithScheduleHook(clock, tasks, pauseHookArmed,
                () -> {
                    // Simulate a pause landing after execute()'s `paused` check
                    // but before the scheduled future is visible to freeze().
                    frozenPause();
                });

        frozen.execute(APP, new ChaosActionRequest("kill", 30));

        assertThat(frozen.isPaused()).isTrue();
        assertThat(tasks).hasSize(1);
        // The late-attached full-TTL task must be cancelled, otherwise it would
        // revert early once the guide resumes.
        assertThat(tasks.get(0).cancelled()).isTrue();

        clock.advance(Duration.ofSeconds(10));
        assertThat(frozen.resume()).isTrue();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(1).delayMillis()).isEqualTo(TimeUnit.SECONDS.toMillis(30));

        tasks.get(0).runIfActive();
        assertThat(gateway.calls).doesNotContain("start:" + APP);
        tasks.get(1).runIfActive();
        assertThat(gateway.calls).contains("start:" + APP);
        assertThat(frozen.activeActions()).isEmpty();
    }

    @Test
    void resumeUpdatesEveryExpiryBeforeUnpausingTheWatchdog() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        properties.setDefaultTtlSeconds(30);
        properties.setMaxTtlSeconds(600);
        properties.setWatchdogIntervalSeconds(60);
        List<CapturedTask> tasks = new CopyOnWriteArrayList<>();
        List<Runnable> watchdogs = new CopyOnWriteArrayList<>();
        AtomicBoolean sweepHookArmed = new AtomicBoolean(false);
        ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
        when(manual.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    CapturedTask task = new CapturedTask(invocation.getArgument(0),
                            invocation.getArgument(1));
                    tasks.add(task);
                    if (sweepHookArmed.compareAndSet(true, false)) {
                        // Watchdog fires the instant resume releases the pause.
                        watchdogs.get(0).run();
                    }
                    return task.future();
                });
        when(manual.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    watchdogs.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        ChaosActionService frozen = new ChaosActionService(gateway, annotator, properties,
                registry, manual, clock);

        frozen.execute(APP, new ChaosActionRequest("kill", 30));
        frozen.execute(APP2, new ChaosActionRequest("kill", 30));
        clock.advance(Duration.ofSeconds(10));
        frozen.pause();
        assertThat(tasks).hasSize(2);
        // The guide stays paused past the original expiry; the remaining TTL is
        // what resume must restore, so a watchdog landing mid-resume must not
        // see the stale frozen expiry.
        clock.advance(Duration.ofSeconds(60));

        sweepHookArmed.set(true);
        Instant resumedAt = clock.instant();
        assertThat(frozen.resume()).isTrue();

        // The mid-resume watchdog must not revert anything: every expiry is
        // already moved to resumedAt + remaining before the paused flag drops.
        assertThat(gateway.calls).doesNotContain("start:" + APP, "start:" + APP2);
        assertThat(frozen.activeActions()).hasSize(2);
        assertThat(tasks).hasSize(4);
        assertThat(tasks.get(2).delayMillis()).isEqualTo(TimeUnit.SECONDS.toMillis(20));
        assertThat(tasks.get(3).delayMillis()).isEqualTo(TimeUnit.SECONDS.toMillis(20));
        assertThat(frozen.activeActions()).allMatch(action ->
                action.expiresAt().equals(resumedAt.plusSeconds(20)));

        // The remaining TTL still reverts both actions.
        clock.advance(Duration.ofSeconds(25));
        watchdogs.get(0).run();
        assertThat(gateway.calls).contains("start:" + APP, "start:" + APP2);
        assertThat(frozen.activeActions()).isEmpty();
    }

    private volatile ChaosActionService pauseHookTarget;

    private void frozenPause() {
        pauseHookTarget.pause();
    }

    private ChaosActionService serviceWithScheduleHook(MutableClock clock,
                                                       List<CapturedTask> tasks,
                                                       AtomicBoolean hookArmed,
                                                       Runnable scheduleHook) {
        ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
        when(manual.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    CapturedTask task = new CapturedTask(invocation.getArgument(0),
                            invocation.getArgument(1));
                    tasks.add(task);
                    if (hookArmed.compareAndSet(true, false)) {
                        scheduleHook.run();
                    }
                    return task.future();
                });
        when(manual.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        ChaosActionService frozen = new ChaosActionService(gateway, annotator, properties,
                registry, manual, clock);
        pauseHookTarget = frozen;
        return frozen;
    }

    /** Scheduled task capture that honours cancel for deterministic races. */
    static final class CapturedTask {

        private final Runnable task;
        private final long delayMillis;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final ScheduledFuture<?> future;

        private CapturedTask(Runnable task, long delayMillis) {
            this.task = task;
            this.delayMillis = delayMillis;
            this.future = mock(ScheduledFuture.class);
            when(this.future.cancel(anyBoolean())).thenAnswer(inv -> cancelled.compareAndSet(false, true));
        }

        private long delayMillis() {
            return delayMillis;
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private void runIfActive() {
            if (!cancelled.get()) {
                task.run();
            }
        }

        private ScheduledFuture<?> future() {
            return future;
        }
    }

    private ChaosActionService serviceWithManualScheduler(MutableClock clock,
                                                          List<Runnable> tasks,
                                                          List<Long> delays,
                                                          List<Runnable> watchdogs) {
        properties.setDefaultTtlSeconds(30);
        properties.setMaxTtlSeconds(600);
        properties.setWatchdogIntervalSeconds(60);
        ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
        when(manual.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    tasks.add(invocation.getArgument(0));
                    delays.add(invocation.getArgument(1));
                    return mock(ScheduledFuture.class);
                });
        when(manual.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    watchdogs.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        return new ChaosActionService(gateway, annotator, properties, registry, manual, clock);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void revertNeverStartsContainerBeforeUnderlyingStopCompleted() throws Exception {
        // Reproduction: with the old schedule-before-apply ordering the TTL task
        // (or the watchdog) could claim the revert and call start() while the
        // Docker stop was still running. Docker answers 304 "already running",
        // the revert is treated as success and the entry is cleared; the stop
        // then completes and the container stays down forever.
        gateway.stopGate = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var future = caller.submit(() -> service.execute(APP, new ChaosActionRequest("kill", 1)));
            assertThat(gateway.stopStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // TTL (1s) elapses while the stop call is still blocked.
            Thread.sleep(1500);
            assertThat(gateway.calls).doesNotContain("start:" + APP);

            gateway.stopGate = null;
            gateway.stopRelease.countDown();
            ChaosActionView view = future.get(5, TimeUnit.SECONDS);

            // The revert must run strictly AFTER the stop finished, exactly once.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(service.activeActions()).isEmpty());
            assertThat(gateway.calls).containsExactly("stop:" + APP, "start:" + APP);
            assertThat(view.status()).isIn("reverted", "active");
            assertThat(counter("chaos.reverts", "action", "kill", "result", "success")).isEqualTo(1.0);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void stopAllDuringApplyDefersRevertUntilStopFinishesThenRevertsImmediately() throws Exception {
        // Finding: stop-all fired while the Docker stop was still running used
        // to cancel the TTL task and then skip the APPLYING action, leaving the
        // container stopped until TTL expiry (up to 600s). The APPLYING gate
        // must stay (no start before the stop finished), but the stop-all
        // intent now reverts at the apply-completion checkpoint.
        gateway.stopGate = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var future = caller.submit(() -> service.execute(APP, new ChaosActionRequest("kill", 5)));
            assertThat(gateway.stopStarted.await(5, TimeUnit.SECONDS)).isTrue();

            StopAllResult result = service.stopAll();
            // Honest counting: the APPLYING action is pending, not reverted.
            assertThat(result.reverted()).isZero();
            assertThat(result.failed()).isZero();
            assertThat(result.pending()).isEqualTo(1);
            assertThat(service.activeActions()).hasSize(1);

            // APPLYING gate intact: no start may run while the stop is in flight.
            Thread.sleep(300);
            assertThat(gateway.calls).doesNotContain("start:" + APP);

            gateway.stopGate = null;
            gateway.stopRelease.countDown();
            future.get(5, TimeUnit.SECONDS);

            // Immediate revert: TTL was 5s and the watchdog only reverts after
            // expiry, so a revert completing well before 5s can only come from
            // the stop-request checkpoint.
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(service.activeActions()).isEmpty());
            assertThat(gateway.calls).containsExactly("stop:" + APP, "start:" + APP);
            assertThat(annotator.events).anySatisfy(event ->
                    assertThat(event.text()).contains("reverted").contains("trigger=stop-all"));
            assertThat(counter("chaos.reverts", "action", "kill", "result", "success")).isEqualTo(1.0);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void stopAllMixesActiveRevertAndApplyingPending() throws Exception {
        gateway.stopGate = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            service.execute(APP2, new ChaosActionRequest("freeze", 5));
            var future = caller.submit(() -> service.execute(APP, new ChaosActionRequest("kill", 5)));
            assertThat(gateway.stopStarted.await(5, TimeUnit.SECONDS)).isTrue();

            StopAllResult result = service.stopAll();
            assertThat(result.reverted()).isEqualTo(1);  // the ACTIVE freeze
            assertThat(result.failed()).isZero();
            assertThat(result.pending()).isEqualTo(1);   // the APPLYING kill
            assertThat(gateway.calls).contains("signal:" + APP2 + ":SIGCONT");
            assertThat(gateway.calls).doesNotContain("start:" + APP);
            // The pending action is still visible until its revert completes.
            assertThat(service.activeActions()).hasSize(1);

            gateway.stopGate = null;
            gateway.stopRelease.countDown();
            future.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(service.activeActions()).isEmpty());
            // Relative order of stop:APP vs signal:APP2:SIGCONT is scheduling-dependent
            // (the gated stop records itself on release); the load-bearing invariants
            // are "all four calls happened" plus "start strictly after the stop".
            assertThat(gateway.calls).containsExactlyInAnyOrder(
                    "signal:" + APP2 + ":SIGSTOP", "stop:" + APP,
                    "signal:" + APP2 + ":SIGCONT", "start:" + APP);
            assertThat(gateway.calls.indexOf("start:" + APP))
                    .isGreaterThan(gateway.calls.indexOf("stop:" + APP));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void indeterminateApplyFailureKeepsActionTrackedForWatchdog() {
        // A transport-level Docker failure on stop is indeterminate: the daemon
        // may have applied the stop even though the response was lost. The
        // action must stay tracked (FAILED) so the watchdog issues the start,
        // instead of silently removing the entry and leaving a stopped container.
        gateway.stopFailure = new DockerUnavailableException("transport died", new RuntimeException("boom"));

        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest("kill", 5)))
                .isInstanceOf(DockerUnavailableException.class);
        assertThat(service.activeActions()).hasSize(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(gateway.calls).contains("start:" + APP));
        assertThat(service.activeActions()).isEmpty();
        assertThat(counter("chaos.reverts", "action", "kill", "result", "success")).isEqualTo(1.0);
    }

    @Test
    void definiteApplyFailureRemovesTheActionWithoutRevertAttempt() {
        // "Container is not running" means the SIGSTOP definitely was not
        // delivered: the action is discarded and no compensating call is made.
        gateway.signalFailure = new InvalidChaosActionException("Container is not running: " + APP);

        assertThatThrownBy(() -> service.execute(APP, new ChaosActionRequest("freeze", 5)))
                .isInstanceOf(InvalidChaosActionException.class);
        assertThat(service.activeActions()).isEmpty();
        assertThat(gateway.calls).doesNotContain("signal:" + APP + ":SIGCONT");
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as("meter %s %s", name, List.of(tags)).isNotNull();
        return counter.count();
    }

    static final class FakeGateway implements ContainerGateway {

        final Set<String> labContainers = Set.of(APP, APP2);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch stopStarted = new CountDownLatch(1);
        final CountDownLatch stopRelease = new CountDownLatch(1);
        volatile boolean failStart;
        /** When non-null, {@link #stop} blocks until {@link #stopRelease} fires. */
        volatile CountDownLatch stopGate;
        /** When non-null, {@link #stop} throws after the gate (and records nothing). */
        volatile RuntimeException stopFailure;
        /** When non-null, {@link #signal} throws before recording the call. */
        volatile RuntimeException signalFailure;

        @Override
        public List<ContainerView> listLabContainers() {
            return labContainers.stream().map(name -> new ContainerView(name, "running", "healthy")).toList();
        }

        @Override
        public String tailLogs(String containerName, int tail) {
            return "";
        }

        @Override
        public ContainerView inspectLabContainer(String containerName) {
            if ("redis".equals(containerName)) {
                throw new ContainerNotAllowedException("Container is not part of the lab: " + containerName);
            }
            if (!labContainers.contains(containerName)) {
                throw new ContainerNotFoundException("Container not found: " + containerName);
            }
            return new ContainerView(containerName, "running", "healthy");
        }

        @Override
        public void stop(String containerName, int graceSeconds) {
            stopStarted.countDown();
            if (stopGate != null) {
                try {
                    stopRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            RuntimeException failure = stopFailure;
            if (failure != null) {
                throw failure;
            }
            calls.add("stop:" + containerName);
        }

        @Override
        public void start(String containerName) {
            calls.add("start:" + containerName);
            if (failStart) {
                throw new IllegalStateException("docker start failed");
            }
        }

        @Override
        public void restart(String containerName, int graceSeconds) {
            calls.add("restart:" + containerName);
        }

        @Override
        public void signal(String containerName, String signal) {
            RuntimeException failure = signalFailure;
            if (failure != null) {
                throw failure;
            }
            calls.add("signal:" + containerName + ":" + signal);
        }
    }

    static final class RecordingAnnotator implements ChaosAnnotator {

        final List<AnnotationEvent> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public void annotate(List<String> tags, String text) {
            events.add(new AnnotationEvent(tags, text));
            if (failing.get()) {
                throw new IllegalStateException("grafana down");
            }
        }
    }

    record AnnotationEvent(List<String> tags, String text) {
    }
}
