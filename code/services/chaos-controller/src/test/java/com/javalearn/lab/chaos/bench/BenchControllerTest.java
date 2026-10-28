package com.javalearn.lab.chaos.bench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code GET /lab/bench/scenarios}: Result envelope, the three
 * UI scenarios with stress/soak excluded, verbatim manifest fields, 503/50303
 * when the manifest is missing or inconsistent, and the scriptExists hint.
 */
class BenchControllerTest {

    @TempDir
    Path dir;

    private Path manifest;

    @BeforeEach
    void setUp() throws Exception {
        manifest = BenchManifestFixture.write(dir, BenchManifestFixture.manifest());
        for (String name : BenchManifestFixture.RUN) {
            BenchManifestFixture.script(dir, name);
        }
    }

    @Test
    void returnsTheUiCatalogWithVerbatimFields() throws Exception {
        MockMvc mvc = mvc(BenchManifestFixture.location(manifest));

        mvc.perform(get("/lab/bench/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.views.ui.length()").value(3))
                .andExpect(jsonPath("$.data.views.ui[0]").value("smoke"))
                .andExpect(jsonPath("$.data.views.ui[1]").value("load"))
                .andExpect(jsonPath("$.data.views.ui[2]").value("spike"))
                .andExpect(jsonPath("$.data.views.ui[3]").doesNotExist())
                .andExpect(jsonPath("$.data.views.bench.length()").value(5))
                .andExpect(jsonPath("$.data.scenarios.smoke.script")
                        .value("scenarios/smoke.js"))
                .andExpect(jsonPath("$.data.scenarios.smoke.serviceOnly").value(false))
                .andExpect(jsonPath("$.data.scenarios.smoke.requires.chaos").value(false))
                .andExpect(jsonPath("$.data.scenarios.smoke.requires.profile").doesNotExist())
                .andExpect(jsonPath("$.data.scenarios.smoke.writesData").value(true))
                .andExpect(jsonPath("$.data.scenarios.smoke.thresholdsExpected").value(true))
                .andExpect(jsonPath("$.data.scenarios.smoke.params.SMOKE_VUS").value(5))
                .andExpect(jsonPath("$.data.scenarios.smoke.scriptExists").value(true))
                .andExpect(jsonPath("$.data.scenarios.traffic.serviceOnly").value(true));
    }

    @Test
    void stressAndSoakAreNeverPartOfTheUiView() throws Exception {
        mvc(BenchManifestFixture.location(manifest)).perform(get("/lab/bench/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.views.ui",
                        org.hamcrest.Matchers.contains("smoke", "load", "spike")));
    }

    @Test
    void missingManifestAnswers503WithItsOwnCode() throws Exception {
        MockMvc mvc = mvc(BenchManifestFixture.location(dir.resolve("scenarios/absent.json")));

        mvc.perform(get("/lab/bench/scenarios"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50303"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    void inconsistentManifestAnswers503WithItsOwnCode() throws Exception {
        ObjectNode broken = BenchManifestFixture.manifest();
        ((ObjectNode) broken.get("scenarios").get("smoke").get("params"))
                .put("UNDECLARED_VUS", 1);
        Path brokenFile = BenchManifestFixture.write(dir.resolve("broken"), broken);

        mvc(BenchManifestFixture.location(brokenFile)).perform(get("/lab/bench/scenarios"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50303"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("UNDECLARED_VUS")));
    }

    @Test
    void uiOutsideBenchAnswers503() throws Exception {
        ObjectNode broken = BenchManifestFixture.manifest();
        ObjectNode views = (ObjectNode) broken.get("views");
        views.putArray("bench").add("smoke").add("load").add("stress").add("spike");
        views.putArray("ui").add("soak");
        Path brokenFile = BenchManifestFixture.write(dir.resolve("broken-views"), broken);

        mvc(BenchManifestFixture.location(brokenFile)).perform(get("/lab/bench/scenarios"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50303"));
    }

    @Test
    void missingScriptsOnlyFlipScriptExists() throws Exception {
        Path bare = BenchManifestFixture.write(dir.resolve("bare"), BenchManifestFixture.manifest());

        mvc(BenchManifestFixture.location(bare)).perform(get("/lab/bench/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarios.smoke.scriptExists").value(false))
                .andExpect(jsonPath("$.data.scenarios.load.scriptExists").value(false));
    }

    private MockMvc mvc(String location) {
        BenchProperties properties = new BenchProperties();
        properties.setScenariosLocation(location);
        BenchScenarioCatalog catalog = new BenchScenarioCatalog(properties,
                new DefaultResourceLoader(), BenchManifestFixture.mapper());
        return MockMvcBuilders.standaloneSetup(new BenchController(catalog))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }
}
