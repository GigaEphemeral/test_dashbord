package com.javalearn.lab.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidScenarioException;
import com.javalearn.lab.chaos.error.ScenarioNotFoundException;
import com.javalearn.lab.chaos.guide.GuideScenario;
import com.javalearn.lab.chaos.guide.GuideScenarioLoader;
import com.javalearn.lab.chaos.guide.GuideScenarioSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuideScenarioLoaderTest {

    private static final String VALID_QUICK = """
            {
              "schemaVersion": 1,
              "id": "quick-failover",
              "name": "Quick failover",
              "description": "Stop app-3 with a short TTL.",
              "durationHintSeconds": 30,
              "events": [
                {"atSeconds": 5, "action": "kill", "target": "jl-app-3", "ttlSeconds": 15,
                 "title": "Stop app-3", "text": "TTL start"}
              ]
            }
            """;

    private static final String VALID_TWO_STEP = """
            {
              "schemaVersion": 1,
              "id": "ha-app-failover",
              "name": "HA app failover",
              "events": [
                {"atSeconds": 60, "action": "kill", "target": "jl-app-1", "ttlSeconds": 60,
                 "title": "Stop app-1", "text": "kill"},
                {"atSeconds": 150, "action": "resume", "target": "jl-app-2",
                 "title": "Resume app-2", "text": "resume"}
              ]
            }
            """;

    @TempDir
    Path tempDir;

    private ChaosProperties properties;
    private GuideScenarioLoader loader;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.setMaxTtlSeconds(600);
        properties.getGuide().setScenarioDir(tempDir.toString());
        loader = new GuideScenarioLoader(new ObjectMapper(), properties);
    }

    @Test
    void listsAndLoadsValidScenarios() throws IOException {
        write("quick-failover.json", VALID_QUICK);
        write("ha-app-failover.json", VALID_TWO_STEP);

        List<GuideScenarioSummary> summaries = loader.list();

        assertThat(summaries).extracting(GuideScenarioSummary::id)
                .containsExactly("ha-app-failover", "quick-failover");
        assertThat(summaries.get(1).stepCount()).isEqualTo(1);
        assertThat(summaries.get(1).durationHintSeconds()).isEqualTo(30.0);

        GuideScenario scenario = loader.load("quick-failover");
        assertThat(scenario.id()).isEqualTo("quick-failover");
        assertThat(scenario.name()).isEqualTo("Quick failover");
        assertThat(scenario.events()).hasSize(1);
        assertThat(scenario.events().get(0).action()).isEqualTo("kill");
        assertThat(scenario.events().get(0).target()).isEqualTo("jl-app-3");
        assertThat(scenario.events().get(0).ttlSeconds()).isEqualTo(15);
        assertThat(scenario.events().get(0).title()).isEqualTo("Stop app-3");
    }

    @Test
    void rejectsWrongSchemaVersion() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("schemaVersion must be 1");
    }

    @Test
    void rejectsUnknownAction() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace("\"action\": \"kill\"", "\"action\": \"explode\""));

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("action must be one of");
    }

    @Test
    void rejectsDatastoreTarget() throws IOException {
        // Widen the allow-list so only the hard datastore rejection can stop it.
        properties.getGuide().setTargetPattern(".*");
        loader = new GuideScenarioLoader(new ObjectMapper(), properties);
        write("quick-failover.json", VALID_QUICK.replace("\"target\": \"jl-app-3\"", "\"target\": \"jl-redis\""));

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("is a datastore");
    }

    @Test
    void rejectsNonLabTarget() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace("\"target\": \"jl-app-3\"", "\"target\": \"jl-nginx\""));

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsOutOfOrderEvents() throws IOException {
        write("ha-app-failover.json", VALID_TWO_STEP
                .replace("\"atSeconds\": 60", "\"atSeconds\": 200"));

        assertThatThrownBy(() -> loader.load("ha-app-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("out of order");
    }

    @Test
    void rejectsMissingTtlAndTtlOnOneShot() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace(", \"ttlSeconds\": 15", ""));
        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("ttlSeconds is required");

        write("ha-app-failover.json", VALID_TWO_STEP.replace(
                "\"target\": \"jl-app-2\",", "\"target\": \"jl-app-2\", \"ttlSeconds\": 10,"));
        assertThatThrownBy(() -> loader.load("ha-app-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("only valid for kill/freeze");
    }

    @Test
    void rejectsTtlAboveConfiguredCeiling() throws IOException {
        properties.setMaxTtlSeconds(10);
        loader = new GuideScenarioLoader(new ObjectMapper(), properties);
        write("quick-failover.json", VALID_QUICK);

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("between 1 and 10");
    }

    @Test
    void rejectsDuplicateOffsetForSameTarget() throws IOException {
        write("quick-failover.json", """
                {
                  "schemaVersion": 1,
                  "id": "quick-failover",
                  "name": "Duplicate",
                  "events": [
                    {"atSeconds": 5, "action": "kill", "target": "jl-app-3", "ttlSeconds": 1},
                    {"atSeconds": 5, "action": "freeze", "target": "jl-app-3", "ttlSeconds": 1}
                  ]
                }
                """);

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("duplicates atSeconds");
    }

    @Test
    void listSkipsInvalidFilesWithoutFailing() throws IOException {
        write("quick-failover.json", VALID_QUICK);
        write("broken.json", "{not-json");
        write("wrong-version.json", VALID_QUICK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 9"));

        List<GuideScenarioSummary> summaries = loader.list();

        assertThat(summaries).extracting(GuideScenarioSummary::id).containsExactly("quick-failover");
    }

    @Test
    void missingDirectoryListsNothingAndRejectsStart() {
        properties.getGuide().setScenarioDir(tempDir.resolve("missing").toString());
        loader = new GuideScenarioLoader(new ObjectMapper(), properties);

        assertThat(loader.list()).isEmpty();
        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(ScenarioNotFoundException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void unknownAndTraversalIdsAreNotFound() {
        assertThatThrownBy(() -> loader.load("unknown"))
                .isInstanceOf(ScenarioNotFoundException.class);
        assertThatThrownBy(() -> loader.load("../etc/passwd"))
                .isInstanceOf(ScenarioNotFoundException.class);
        assertThatThrownBy(() -> loader.load(""))
                .isInstanceOf(ScenarioNotFoundException.class);
    }

    @Test
    void parsesOptionalExplainBlockAndTreatsAnEmptyOneAsAbsent() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace(
                "\"title\": \"Stop app-3\", \"text\": \"TTL start\"",
                "\"title\": \"Stop app-3\", \"text\": \"TTL start\","
                        + " \"explain\": {\"symptom\": \"app-3 指标消失\", \"rootCause\": \"实例被停止\"}"));

        var event = loader.load("quick-failover").events().get(0);

        assertThat(event.explain().symptom()).isEqualTo("app-3 指标消失");
        assertThat(event.explain().rootCause()).isEqualTo("实例被停止");
        assertThat(event.explain().fix()).isNull();
        assertThat(event.explain().expectation()).isNull();

        write("quick-failover.json", VALID_QUICK.replace(
                "\"title\": \"Stop app-3\", \"text\": \"TTL start\"", "\"explain\": {}"));
        assertThat(loader.load("quick-failover").events().get(0).explain()).isNull();
    }

    @Test
    void rejectsMalformedExplainBlocks() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace(
                "\"title\": \"Stop app-3\", \"text\": \"TTL start\"",
                "\"title\": \"Stop app-3\", \"explain\": \"app-3 指标消失\""));
        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("explain must be an object");

        write("quick-failover.json", VALID_QUICK.replace(
                "\"title\": \"Stop app-3\", \"text\": \"TTL start\"",
                "\"title\": \"Stop app-3\", \"explain\": {\"fix\": \"  \"}"));
        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("explain.fix must be a non-empty string");
    }

    @Test
    void acceptsIntegerValuedDecimalsLikeK6() throws IOException {
        // JS JSON parses 1.0 / 15.0 as the integers 1 / 15 and k6 accepts them;
        // the controller must not reject the same file the k6 driver validates.
        write("quick-failover.json", VALID_QUICK
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.0")
                .replace("\"ttlSeconds\": 15", "\"ttlSeconds\": 15.0"));

        GuideScenario scenario = loader.load("quick-failover");

        assertThat(scenario.events().get(0).ttlSeconds()).isEqualTo(15);
    }

    @Test
    void rejectsFractionalNumbers() throws IOException {
        write("quick-failover.json", VALID_QUICK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.5"));
        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("schemaVersion must be an integer");

        write("ha-app-failover.json", VALID_TWO_STEP
                .replace("\"ttlSeconds\": 60", "\"ttlSeconds\": 1.5"));
        assertThatThrownBy(() -> loader.load("ha-app-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("ttlSeconds must be an integer");
    }

    @Test
    void rejectsDuplicateScenarioIdAcrossFiles() throws IOException {
        write("a.json", VALID_QUICK.replace("\"id\": \"quick-failover\"", "\"id\": \"dup\""));
        write("b.json", VALID_QUICK
                .replace("\"id\": \"quick-failover\"", "\"id\": \"dup\"")
                .replace("\"target\": \"jl-app-3\"", "\"target\": \"jl-app-2\""));

        assertThatThrownBy(() -> loader.load("dup"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("multiple files")
                .hasMessageContaining("a.json")
                .hasMessageContaining("b.json");
        assertThat(loader.list()).extracting(GuideScenarioSummary::id).isEmpty();
    }

    @Test
    void loadsByEmbeddedIdNotFileName() throws IOException {
        write("renamed.json", VALID_QUICK.replace("\"id\": \"quick-failover\"", "\"id\": \"the-thing\""));

        assertThat(loader.load("the-thing").id()).isEqualTo("the-thing");
        // A file name that does not carry the embedded id must not resolve.
        assertThatThrownBy(() -> loader.load("renamed"))
                .isInstanceOf(ScenarioNotFoundException.class);
        assertThat(loader.list()).extracting(GuideScenarioSummary::id).containsExactly("the-thing");
    }

    @Test
    void startStillReportsTheInvalidFileWhenTheNameMatchesTheId() throws IOException {
        // A broken quick-failover.json is rejected with its concrete reason (400),
        // not hidden behind a 404, so the operator sees what to fix.
        write("quick-failover.json", VALID_QUICK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 7"));

        assertThatThrownBy(() -> loader.load("quick-failover"))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("schemaVersion must be 1");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }
}
