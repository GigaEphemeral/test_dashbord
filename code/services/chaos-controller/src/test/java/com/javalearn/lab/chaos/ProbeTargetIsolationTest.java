package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionType;
import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.cluster.ProbeTargetGuard;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ProbeTargetProtectedException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.model.ExecResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Stage2 B0-08 slice B: the MySQL cluster probe container and the fault target
 * are forced apart.
 *
 * <p>The db-failover drill kills the PRIMARY while the probe keeps reporting
 * membership from one fixed node. When those two are the same container, the
 * fault blinds the observer exactly when the panel should show the failover, so
 * a destructive action on the probe target is refused with
 * {@link ProbeTargetProtectedException} (HTTP 403 / {@code 40302}) before any
 * Docker call. Recovery actions ({@code start}/{@code resume}) stay allowed -
 * they are what brings the probe back.</p>
 */
class ProbeTargetIsolationTest {

    /** The lab HA tier: the drill may fault any member (datastore allow-list open). */
    private static final String MYSQL_ALLOW_LIST = "^jl-mysql-[1-3]$";
    private static final String PROBE_CONTAINER = "jl-mysql-3";
    private static final ChaosAnnotator NO_ANNOTATIONS = (tags, text) -> {
    };

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.setDefaultTtlSeconds(60);
        properties.setMaxTtlSeconds(600);
        properties.setWatchdogIntervalSeconds(0);
        properties.setActionTimeoutSeconds(0);
        properties.getDatastore().setAllowedTargetPattern(MYSQL_ALLOW_LIST);
        properties.getMysqlCluster().setContainer(PROBE_CONTAINER);
        gateway = new RecordingGateway();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        registry.close();
    }

    private ChaosProperties properties;
    private RecordingGateway gateway;
    private ScheduledExecutorService scheduler;
    private SimpleMeterRegistry registry;

    private ChaosActionService newService() {
        return new ChaosActionService(gateway, NO_ANNOTATIONS, properties, registry, scheduler);
    }

    // --- the refusal ----------------------------------------------------------

    @Test
    void killOnTheProbeContainerIsRefusedBeforeAnyDockerCall() {
        ChaosActionService service = newService();

        assertThatThrownBy(() -> service.execute(PROBE_CONTAINER, new ChaosActionRequest("kill", 30)))
                .isInstanceOf(ProbeTargetProtectedException.class)
                .hasMessageContaining("is the MySQL cluster probe container")
                .hasMessageContaining("chaos.mysql-cluster.container")
                .hasMessageContaining("CHAOS_MYSQL_CLUSTER_CONTAINER")
                .hasMessageContaining("freeze")
                .hasMessageContaining("start/resume on it stay allowed");
        assertThat(gateway.calls).isEmpty();
        assertThat(counter("chaos.actions", "action", "kill", "target", PROBE_CONTAINER,
                "result", "rejected")).isEqualTo(1.0);
    }

    @Test
    void freezeAndRestartAreRefusedOnTheProbeContainerToo() {
        ChaosActionService service = newService();

        for (String action : List.of("freeze", "restart")) {
            assertThatThrownBy(() -> service.execute(PROBE_CONTAINER, new ChaosActionRequest(action, 10)))
                    .as("action %s", action)
                    .isInstanceOf(ProbeTargetProtectedException.class);
        }
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void anIdReferenceResolvingToTheProbeContainerIsRefused() {
        // Docker resolves an id prefix to the same container the name addresses, so
        // the rule is checked against the canonical inspect result, not the string.
        gateway.resolvedNames.put("ab12cd34", PROBE_CONTAINER);
        ChaosActionService service = newService();

        assertThatThrownBy(() -> service.execute("ab12cd34", new ChaosActionRequest("kill", 30)))
                .isInstanceOf(ProbeTargetProtectedException.class)
                .hasMessageContaining("is the MySQL cluster probe container");
        assertThat(gateway.calls).isEmpty();
    }

    // --- what stays allowed ---------------------------------------------------

    @Test
    void startAndResumeOnTheProbeContainerAreNeverRefused() {
        ChaosActionService service = newService();

        service.execute(PROBE_CONTAINER, new ChaosActionRequest("start", null));
        service.execute(PROBE_CONTAINER, new ChaosActionRequest("resume", null));

        assertThat(gateway.calls).containsExactly("start:" + PROBE_CONTAINER,
                "signal:" + PROBE_CONTAINER + ":SIGCONT");
    }

    @Test
    void theOtherClusterMembersAreFairGame() {
        ChaosActionService service = newService();

        service.execute("jl-mysql-2", new ChaosActionRequest("kill", 30));

        assertThat(gateway.calls).containsExactly("stop:jl-mysql-2");
    }

    @Test
    void aDisabledProbeHasNothingToProtect() {
        // Dev stack without the db-ha tier: enabled=false registers no meters and
        // the guard must not refuse the operator's own MySQL container.
        properties.getMysqlCluster().setEnabled(false);
        ChaosActionService service = newService();

        service.execute(PROBE_CONTAINER, new ChaosActionRequest("kill", 30));

        assertThat(gateway.calls).containsExactly("stop:" + PROBE_CONTAINER);
    }

    // --- guard unit -----------------------------------------------------------

    @Test
    void guardComparesWholeNamesOnly() {
        ProbeTargetGuard guard = new ProbeTargetGuard(PROBE_CONTAINER, true);

        assertThat(guard.targetsProbe(PROBE_CONTAINER)).isTrue();
        assertThat(guard.targetsProbe(" " + PROBE_CONTAINER + " ")).isTrue();
        // neither a prefix nor a suffix may be mistaken for the probe container
        for (String other : List.of("jl-mysql-30", "jl-mysql-1", "xjl-mysql-3", "mysql-3")) {
            assertThat(guard.targetsProbe(other)).as("target %s", other).isFalse();
            assertThat(guard.rejectionReason(other)).isNull();
        }
        assertThat(guard.rejectionReason(PROBE_CONTAINER)).contains(PROBE_CONTAINER);
    }

    @Test
    void guardIsInactiveWithoutAConfiguredContainerOrWhenDisabled() {
        assertThat(new ProbeTargetGuard(PROBE_CONTAINER, false).active()).isFalse();
        assertThat(new ProbeTargetGuard("", true).active()).isFalse();
        assertThat(new ProbeTargetGuard("   ", true).active()).isFalse();
        assertThat(new ProbeTargetGuard(null, true).active()).isFalse();
        assertThat(new ProbeTargetGuard(null, true).rejectionReason("jl-mysql-3")).isNull();
    }

    @Test
    void guardFollowsThePropertiesItWasBuiltFrom() {
        // ChaosProperties code default is jl-mysql-1 (compose overrides it to -3).
        ProbeTargetGuard byCode = ProbeTargetGuard.from(new ChaosProperties());
        assertThat(byCode.probeContainer()).isEqualTo("jl-mysql-1");
        assertThat(byCode.targetsProbe("jl-mysql-1")).isTrue();

        properties.getMysqlCluster().setContainer(" jl-mysql-2 ");
        assertThat(ProbeTargetGuard.from(properties).targetsProbe("jl-mysql-2")).isTrue();
    }

    @Test
    void onlyDestructiveActionTypesAreGuarded() {
        assertThat(ChaosActionType.KILL.destructive()).isTrue();
        assertThat(ChaosActionType.FREEZE.destructive()).isTrue();
        assertThat(ChaosActionType.RESTART.destructive()).isTrue();
        assertThat(ChaosActionType.START.destructive()).isFalse();
        assertThat(ChaosActionType.RESUME.destructive()).isFalse();
    }

    @Test
    void refusalDoesNotLeakIntoTheActiveActionRegistry() {
        ChaosActionService service = newService();

        assertThatCode(() -> service.execute(PROBE_CONTAINER, new ChaosActionRequest("kill", 30)))
                .isInstanceOf(ProbeTargetProtectedException.class);
        // nothing was registered, so the same target can still be recovered by start
        assertThat(service.activeActions()).isEmpty();
        service.execute(PROBE_CONTAINER, new ChaosActionRequest("start", null));
        assertThat(gateway.calls).containsExactly("start:" + PROBE_CONTAINER);
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as("meter %s %s", name, List.of(tags)).isNotNull();
        return counter.count();
    }

    /** Every name is lab-labeled and allow-listed here, so only the probe rule can refuse. */
    private static final class RecordingGateway implements ContainerGateway {

        private static final Set<String> LAB_CONTAINERS =
                Set.of("jl-mysql-1", "jl-mysql-2", "jl-mysql-3", "jl-app-1");
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final Map<String, String> resolvedNames = new ConcurrentHashMap<>();

        @Override
        public List<ContainerView> listLabContainers() {
            return LAB_CONTAINERS.stream()
                    .map(name -> new ContainerView(name, "running", "healthy"))
                    .toList();
        }

        @Override
        public String tailLogs(String containerName, int tail) {
            return "";
        }

        @Override
        public ContainerView inspectLabContainer(String containerName) {
            return new ContainerView(resolvedNames.getOrDefault(containerName, containerName),
                    "running", "healthy");
        }

        @Override
        public void stop(String containerName, int graceSeconds) {
            calls.add("stop:" + containerName);
        }

        @Override
        public void start(String containerName) {
            calls.add("start:" + containerName);
        }

        @Override
        public void restart(String containerName, int graceSeconds) {
            calls.add("restart:" + containerName);
        }

        @Override
        public void signal(String containerName, String signal) {
            calls.add("signal:" + containerName + ":" + signal);
        }

        @Override
        public ExecResult execInContainer(String containerName, List<String> command,
                                          List<String> env, String stdin, int timeoutSeconds) {
            calls.add("exec:" + containerName);
            return new ExecResult("", "", 0, false);
        }
    }
}
