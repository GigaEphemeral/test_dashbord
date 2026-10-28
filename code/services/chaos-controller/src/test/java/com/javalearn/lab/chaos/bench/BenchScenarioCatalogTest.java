package com.javalearn.lab.chaos.bench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation contract of the bench catalog: the manifest is accepted only when
 * the node reader would accept it too. Every refusal is a
 * {@link BenchUnavailableException} (HTTP 503/50303), never a partial or empty
 * catalog.
 */
class BenchScenarioCatalogTest {

    @TempDir
    Path dir;

    @Test
    void validManifestProjectsViewsScenariosAndScripts() throws Exception {
        for (String name : BenchManifestFixture.RUN) {
            BenchManifestFixture.script(dir, name);
        }
        BenchManifestFixture.script(dir, "traffic");
        Path file = BenchManifestFixture.write(dir, BenchManifestFixture.manifest());

        BenchScenarioCatalog.Snapshot snapshot = catalog(BenchManifestFixture.location(file)).load();

        assertEquals("OK", snapshot.status());
        assertEquals(1, snapshot.version());
        assertEquals(BenchManifestFixture.UI, snapshot.views().get("ui"));
        assertEquals(BenchManifestFixture.BENCH, snapshot.views().get("bench"));
        assertEquals(BenchManifestFixture.RUN, snapshot.views().get("run"));
        assertEquals(6, snapshot.scenarios().size());

        BenchScenarioCatalog.Scenario smoke = snapshot.scenarios().get("smoke");
        assertEquals("scenarios/smoke.js", smoke.script());
        assertFalse(smoke.serviceOnly());
        assertEquals(false, smoke.requires().chaos());
        assertEquals(false, smoke.requires().cache());
        assertNull(smoke.requires().profile());
        assertTrue(smoke.writesData());
        assertTrue(smoke.thresholdsExpected());
        assertEquals(5, smoke.params().get("SMOKE_VUS"));
        assertTrue(smoke.scriptExists());

        BenchScenarioCatalog.Scenario traffic = snapshot.scenarios().get("traffic");
        assertTrue(traffic.serviceOnly());
        for (String view : BenchScenarioCatalog.VIEW_NAMES) {
            assertFalse(snapshot.views().get(view).contains("traffic"),
                    "service-only traffic must not be in views." + view);
        }
    }

    @Test
    void uiMustBeASubsetOfBench() throws Exception {
        ObjectNode manifest = BenchManifestFixture.manifest();
        ObjectNode views = (ObjectNode) manifest.get("views");
        // soak stays in run but leaves bench, so ui no longer has a bench home.
        views.putArray("bench").add("smoke").add("load").add("stress").add("spike");
        views.putArray("ui").add("smoke").add("soak");
        BenchManifestFixture.write(dir, manifest);

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog().load());
        assertTrue(error.getMessage().contains("views.ui must be a subset of views.bench"),
                error.getMessage());
    }

    @Test
    void viewNameMustExistInScenarios() throws Exception {
        ObjectNode manifest = BenchManifestFixture.manifest();
        ((ObjectNode) manifest.get("views")).withArray("run").add("ghost");
        BenchManifestFixture.write(dir, manifest);

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog().load());
        assertTrue(error.getMessage().contains("unknown scenario \"ghost\""), error.getMessage());
    }

    @Test
    void paramsKeyMustBeDeclaredInEnv() throws Exception {
        ObjectNode manifest = BenchManifestFixture.manifest();
        ((ObjectNode) manifest.get("scenarios").get("smoke").get("params"))
                .put("UNDECLARED_VUS", 5);
        BenchManifestFixture.write(dir, manifest);

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog().load());
        assertTrue(error.getMessage().contains("params.UNDECLARED_VUS is not declared"),
                error.getMessage());
    }

    @Test
    void missingRequiredFieldIsRejected() throws Exception {
        ObjectNode manifest = BenchManifestFixture.manifest();
        ((ObjectNode) manifest.get("scenarios").get("smoke")).remove("script");
        BenchManifestFixture.write(dir, manifest);

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog().load());
        assertTrue(error.getMessage().contains("scenarios.smoke.script"), error.getMessage());
    }

    @Test
    void serviceOnlyScenarioMustNotAppearInAView() throws Exception {
        ObjectNode manifest = BenchManifestFixture.manifest();
        ((ObjectNode) manifest.get("views")).withArray("run").add("traffic");
        ((ObjectNode) manifest.get("views")).withArray("bench").add("traffic");
        BenchManifestFixture.write(dir, manifest);

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog().load());
        assertTrue(error.getMessage().contains("service-only scenario \"traffic\""),
                error.getMessage());
    }

    @Test
    void forwardedNameMustBeConsumed() throws Exception {
        ObjectNode manifest = BenchManifestFixture.manifest();
        ((ObjectNode) manifest.get("forwarding")).withArray("common").add("UNUSED_VUS");
        BenchManifestFixture.write(dir, manifest);

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog().load());
        assertTrue(error.getMessage().contains("UNUSED_VUS") && error.getMessage()
                .contains("no scenario or runtimeEnv declares it"), error.getMessage());
    }

    @Test
    void missingManifestIsUnavailable() {
        String location = BenchManifestFixture.location(dir.resolve("scenarios/manifest.json"));
        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog(location).load());
        assertTrue(error.getMessage().contains("not found"), error.getMessage());
    }

    @Test
    void scriptExistsIsFalseWithoutFailingTheCatalog() throws Exception {
        Path file = BenchManifestFixture.write(dir, BenchManifestFixture.manifest());

        BenchScenarioCatalog.Snapshot snapshot = catalog(BenchManifestFixture.location(file)).load();

        assertEquals(6, snapshot.scenarios().size());
        snapshot.scenarios().values().forEach(scenario ->
                assertFalse(scenario.scriptExists(), scenario.script() + " must not exist"));
    }

    @Test
    void invalidJsonIsUnavailable() throws Exception {
        Path scenarios = Files.createDirectories(dir.resolve("scenarios"));
        Path file = scenarios.resolve("manifest.json");
        Files.writeString(file, "{not json");

        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> catalog(BenchManifestFixture.location(file)).load());
        assertTrue(error.getMessage().contains("cannot read scenario manifest"),
                error.getMessage());
    }

    /**
     * Guard against drift with the node reader: the real repository manifest must
     * pass the same validation. Skipped when the module is built outside the repo
     * checkout (the path is resolved from the working directory).
     */
    @Test
    void realRepositoryManifestIsAcceptedWhenPresent() {
        Path manifest = repoManifest();
        Assumptions.assumeTrue(manifest != null, "repository manifest not found from "
                + Path.of("").toAbsolutePath());

        BenchScenarioCatalog.Snapshot snapshot = catalog(BenchManifestFixture.location(manifest))
                .load();

        assertEquals(9, snapshot.views().get("run").size());
        assertEquals(5, snapshot.views().get("bench").size());
        assertEquals(3, snapshot.views().get("ui").size());
        assertEquals("smoke", snapshot.views().get("ui").get(0));
        assertFalse(snapshot.views().get("ui").contains("stress"));
        assertFalse(snapshot.views().get("ui").contains("soak"));
        assertTrue(snapshot.scenarios().get("smoke").scriptExists());
        assertTrue(snapshot.scenarios().get("traffic").serviceOnly());
    }

    private BenchScenarioCatalog catalog() {
        return catalog(BenchManifestFixture.location(dir.resolve("scenarios/manifest.json")));
    }

    private BenchScenarioCatalog catalog(String location) {
        BenchProperties properties = new BenchProperties();
        properties.setScenariosLocation(location);
        return new BenchScenarioCatalog(properties, new DefaultResourceLoader(),
                BenchManifestFixture.mapper());
    }

    private static Path repoManifest() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve("code/lab/k6/scenarios/manifest.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
