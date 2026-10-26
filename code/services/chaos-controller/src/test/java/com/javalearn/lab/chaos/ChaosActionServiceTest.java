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
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as("meter %s %s", name, List.of(tags)).isNotNull();
        return counter.count();
    }

    static final class FakeGateway implements ContainerGateway {

        final Set<String> labContainers = Set.of(APP, APP2);
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean failStart;

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
