package com.javalearn.lab.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.config.DatastoreTargetPolicy;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.InvalidScenarioException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.guide.GuideScenario;
import com.javalearn.lab.chaos.guide.GuideScenarioLoader;
import com.javalearn.lab.chaos.guide.GuideScenarioSummary;
import com.javalearn.lab.chaos.model.ContainerView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-1c: the absolute datastore prohibition became a closed-by-default
 * allow-list ({@code chaos.datastore.allowed-target-pattern}).
 *
 * <p>Covers both enforcement points - {@code ChaosActionService} (the real
 * boundary) and {@code GuideScenarioLoader} (scenario pre-flight) - plus the
 * startup validation of the configured regex and the shipped
 * {@code redis-sentinel-failover} scenario. The mirrored JS rules live in
 * {@code code/lab/k6/test/chaos-schema.test.mjs}.</p>
 */
class DatastoreTargetAllowListTest {

    /** The HA topology of step 24a: three data nodes plus three Sentinels. */
    private static final String HA_TARGET_PATTERN = "^jl-(app|redis|sentinel)-[0-9]+$";
    private static final String HA_REDIS_ALLOW_LIST = "^jl-redis-[0-9]+$";
    private static final ChaosAnnotator NO_ANNOTATIONS = (tags, text) -> {
    };

    private static final List<String> DATASTORE_NAMES = List.of(
            "jl-redis", "jl-redis-1", "jl-redis-2", "jl-redis-1-exporter",
            "jl-mysql", "jl-mysql-primary", "jl-rabbitmq", "jl-mongo", "jl-postgres");

    @TempDir
    Path tempDir;

    private ChaosProperties properties;
    private RecordingGateway gateway;
    private ScheduledExecutorService scheduler;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.setDefaultTtlSeconds(60);
        properties.setMaxTtlSeconds(600);
        // No watchdog noise: these tests only assert the synchronous decision.
        properties.setWatchdogIntervalSeconds(0);
        properties.setActionTimeoutSeconds(0);
        gateway = new RecordingGateway();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private ChaosActionService newService() {
        return new ChaosActionService(gateway, NO_ANNOTATIONS, properties, registry, scheduler);
    }

    // --- default: everything datastore-shaped stays refused -------------------

    @Test
    void defaultConfigRefusesEveryDatastoreTarget() {
        ChaosActionService service = newService();

        for (String target : DATASTORE_NAMES) {
            assertThatThrownBy(() -> service.execute(target, new ChaosActionRequest("kill", 30)))
                    .as("target %s", target)
                    .isInstanceOf(ContainerNotAllowedException.class)
                    .hasMessageContaining("is a datastore; datastore actions are forbidden")
                    .hasMessageContaining("chaos.datastore.allowed-target-pattern is empty");
        }
        assertThat(gateway.calls).isEmpty();
        for (String target : DATASTORE_NAMES) {
            assertThat(counter("chaos.actions", "action", "kill", "target", target,
                    "result", "rejected")).isEqualTo(1.0);
        }
    }

    @Test
    void loaderRefusesEveryDatastoreEvenWithABroadAppPattern() throws IOException {
        // Deliberately widen the guide pattern: only the datastore rule can stop it.
        properties.getGuide().setTargetPattern(".*");
        properties.getGuide().setScenarioDir(tempDir.toString());
        GuideScenarioLoader loader = newLoader();

        for (String target : List.of("jl-redis", "jl-mysql", "jl-rabbitmq", "jl-redis-1")) {
            write("quick-failover.json", scenarioFor("quick-failover", target));
            assertThatThrownBy(() -> loader.load("quick-failover"))
                    .as("target %s", target)
                    .isInstanceOf(InvalidScenarioException.class)
                    .hasMessageContaining("is a datastore; datastore actions are forbidden")
                    .hasMessageContaining("chaos.datastore.allowed-target-pattern is empty");
        }
    }

    @Test
    void allowListedDatastoreStillNeedsTheGuideTargetPattern() throws IOException {
        // The allow-list alone is not enough: the guide pattern is a separate gate,
        // so a stale default pattern keeps the Sentinel scenario invisible.
        properties.getDatastore().setAllowedTargetPattern(HA_REDIS_ALLOW_LIST);
        properties.getGuide().setScenarioDir(tempDir.toString());
        GuideScenarioLoader loader = newLoader();
        write("quick-failover.json", scenarioFor("quick-failover", "jl-redis-1"));

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("does not match");
    }

    // --- configured allow-list: admits exactly the matching HA nodes ----------

    @Test
    void allowListAdmitsOnlyTheMatchingHaRedisNodes() {
        properties.getDatastore().setAllowedTargetPattern(HA_REDIS_ALLOW_LIST);
        ChaosActionService service = newService();

        for (String target : List.of("jl-redis-1", "jl-redis-2", "jl-redis-3")) {
            service.execute(target, new ChaosActionRequest("kill", 30));
            assertThat(gateway.calls).contains("stop:" + target);
        }
        // one-shot actions on an allowed datastore go through as well
        service.execute("jl-redis-1", new ChaosActionRequest("start", null));
        assertThat(gateway.calls).contains("start:jl-redis-1");

        // standalone redis, the shared MySQL, the exporter sidecar and any name
        // outside the pattern stay refused, and the refusal names the refusing rule
        for (String target : List.of("jl-redis", "jl-mysql", "jl-redis-1-exporter", "jl-rabbitmq")) {
            assertThatThrownBy(() -> service.execute(target, new ChaosActionRequest("freeze", 10)))
                    .as("target %s", target)
                    .isInstanceOf(ContainerNotAllowedException.class)
                    .hasMessageContaining("is a datastore and is refused by"
                            + " chaos.datastore.allowed-target-pattern=\"" + HA_REDIS_ALLOW_LIST + "\"");
        }
        assertThat(gateway.calls).doesNotContain("signal:jl-redis:SIGSTOP", "stop:jl-mysql");
    }

    @Test
    void loaderAdmitsOnlyTheMatchingHaRedisNodes() throws IOException {
        properties.getGuide().setTargetPattern(HA_TARGET_PATTERN);
        properties.getDatastore().setAllowedTargetPattern(HA_REDIS_ALLOW_LIST);
        properties.getGuide().setScenarioDir(tempDir.toString());
        GuideScenarioLoader loader = newLoader();

        write("ha-redis.json", scenarioFor("ha-redis-drill", "jl-redis-2"));
        GuideScenario allowed = loader.load("ha-redis-drill");
        assertThat(allowed.events()).singleElement()
                .satisfies(event -> assertThat(event.target()).isEqualTo("jl-redis-2"));

        // a Sentinel is not datastore-shaped: the guide pattern decides for it
        write("ha-sentinel.json", scenarioFor("ha-sentinel-drill", "jl-sentinel-1"));
        assertThat(loader.load("ha-sentinel-drill").events().get(0).target())
                .isEqualTo("jl-sentinel-1");

        // standalone jl-redis and jl-mysql remain refused even with a broad app
        // pattern, because the allow-list only admits jl-redis-<n>
        properties.getGuide().setTargetPattern("^jl-.*$");
        GuideScenarioLoader broadLoader = newLoader();
        for (String target : List.of("jl-redis", "jl-mysql", "jl-redis-1-exporter", "jl-rabbitmq")) {
            write("quick-failover.json", scenarioFor("quick-failover", target));
            assertThatThrownBy(() -> broadLoader.load("quick-failover"))
                    .as("target %s", target)
                    .isInstanceOf(InvalidScenarioException.class)
                    .hasMessageContaining("is a datastore and is refused by"
                            + " chaos.datastore.allowed-target-pattern=\"" + HA_REDIS_ALLOW_LIST + "\"");
        }
    }

    @Test
    void nonDatastoreTargetsAreUntouchedByTheRule() {
        ChaosActionService service = newService();

        service.execute("jl-app-1", new ChaosActionRequest("kill", 30));
        service.execute("jl-sentinel-1", new ChaosActionRequest("restart", null));

        assertThat(gateway.calls).containsExactly("stop:jl-app-1", "restart:jl-sentinel-1");
    }

    // --- fail fast on a broken regex -----------------------------------------

    @Test
    void invalidAllowListPatternFailsFastAtStartup() throws IOException {
        properties.getDatastore().setAllowedTargetPattern("^jl-redis-(");
        properties.getGuide().setScenarioDir(tempDir.toString());

        assertThatThrownBy(this::newService)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.datastore.allowed-target-pattern"
                        + " is not a valid regular expression");
        assertThatThrownBy(this::newLoader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chaos.datastore.allowed-target-pattern"
                        + " is not a valid regular expression");
        // a blank value is the closed default, not a broken pattern
        properties.getDatastore().setAllowedTargetPattern("   ");
        assertThat(newLoader()).isNotNull();
        assertThat(newService()).isNotNull();
        DatastoreTargetPolicy closed = DatastoreTargetPolicy.from(properties);
        assertThat(closed.rejectionReason("jl-redis")).contains("is empty");
        assertThat(closed.permits("jl-app-1")).isTrue();
    }

    // --- the shipped Sentinel scenario ---------------------------------------

    @Test
    void shippedSentinelScenarioValidatesInJavaAndIsInvisibleByDefault() {
        Path scenarios = realScenarioDir();
        assumeScenarioRepoAvailable(scenarios);
        ChaosProperties closed = new ChaosProperties();
        closed.getGuide().setScenarioDir(scenarios.toString());
        List<String> listedIds = new GuideScenarioLoader(new ObjectMapper(), closed).list().stream()
                .map(GuideScenarioSummary::id)
                .toList();
        // default (app-only) config: the datastore scenario is invalid and skipped,
        // never half-loaded
        assertThat(listedIds).contains("quick-failover", "ha-app-failover")
                .doesNotContain("redis-sentinel-failover");

        ChaosProperties ha = new ChaosProperties();
        ha.getGuide().setScenarioDir(scenarios.toString());
        ha.getGuide().setTargetPattern(HA_TARGET_PATTERN);
        ha.getDatastore().setAllowedTargetPattern(HA_REDIS_ALLOW_LIST);
        GuideScenarioLoader loader = new GuideScenarioLoader(new ObjectMapper(), ha);

        assertThat(loader.list()).extracting(GuideScenarioSummary::id)
                .contains("redis-sentinel-failover");
        GuideScenario scenario = loader.load("redis-sentinel-failover");
        assertThat(scenario.name()).isNotBlank();
        assertThat(scenario.events()).extracting(event -> event.action() + ":" + event.target())
                .containsExactly("kill:jl-redis-1", "resume:jl-redis-2", "start:jl-redis-1");
        assertThat(scenario.events().get(0).ttlSeconds()).isEqualTo(45);
        assertThat(scenario.events()).allSatisfy(event ->
                assertThat(event.explain()).isNotNull());

        // and every step of it is executable by the action service under the same rule
        properties = ha;
        properties.setWatchdogIntervalSeconds(0);
        properties.setActionTimeoutSeconds(0);
        ChaosActionService service = newService();
        for (var event : scenario.events()) {
            service.execute(event.target(),
                    new ChaosActionRequest(event.action(), event.ttlSeconds()));
        }
        assertThat(gateway.calls).containsExactly(
                "stop:jl-redis-1", "signal:jl-redis-2:SIGCONT", "start:jl-redis-1");
    }

    private GuideScenarioLoader newLoader() {
        return new GuideScenarioLoader(new ObjectMapper(), properties);
    }

    /** Resolves code/lab/scenarios from the module (surefire) or reactor working dir. */
    private static Path realScenarioDir() {
        Path moduleRelative = Path.of("..", "..", "lab", "scenarios").toAbsolutePath().normalize();
        return Files.isDirectory(moduleRelative)
                ? moduleRelative
                : Path.of("lab", "scenarios").toAbsolutePath().normalize();
    }

    private static void assumeScenarioRepoAvailable(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("expected shared scenarios at " + dir
                    + " - the repository layout changed");
        }
    }

    private static String scenarioFor(String id, String target) {
        return """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "name": "Sentinel drill",
                  "events": [
                    {"atSeconds": 5, "action": "kill", "target": "%s", "ttlSeconds": 15,
                     "title": "stop", "text": "stop"}
                  ]
                }
                """.formatted(id, target);
    }

    private void write(String name, String json) throws IOException {
        Files.writeString(tempDir.resolve(name), json);
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as("meter %s %s", name, List.of(tags)).isNotNull();
        return counter.count();
    }

    /** Every name is lab-labeled here, so only the datastore rule can refuse. */
    static final class RecordingGateway implements ContainerGateway {

        final Set<String> labContainers = Set.of("jl-app-1", "jl-sentinel-1", "jl-redis",
                "jl-redis-1", "jl-redis-2", "jl-redis-3", "jl-redis-1-exporter", "jl-mysql",
                "jl-mysql-primary", "jl-rabbitmq", "jl-mongo", "jl-postgres");
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public List<ContainerView> listLabContainers() {
            return labContainers.stream()
                    .map(name -> new ContainerView(name, "running", "healthy"))
                    .toList();
        }

        @Override
        public String tailLogs(String containerName, int tail) {
            return "";
        }

        @Override
        public ContainerView inspectLabContainer(String containerName) {
            return new ContainerView(containerName, "running", "healthy");
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
    }
}
