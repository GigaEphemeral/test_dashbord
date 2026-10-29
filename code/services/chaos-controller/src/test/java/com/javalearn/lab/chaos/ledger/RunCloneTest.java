package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunCloneTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void cloneRequestKeepsParamsConfigNodesAndFaultTimeline() {
        RunRecord run = new RunRecord("run-1", "load", "基线", "app-1,app-2",
                RunStatus.COMPLETED, "abc", "tester", NOW, NOW, NOW, NOW, false);
        Map<String, String> groups = Map.of(
                "request", "{\"TRAFFIC_MODE\":\"target-instance\",\"TARGET_INSTANCE\":\"app-2\"}",
                "config", "{\"jvm\":{\"xmxMb\":512}}",
                "faultSchedule", "[{\"atSeconds\":10,\"target\":\"app-2\",\"action\":\"freeze\"}]");

        LedgerViews.RunCreateRequest request = RunLedgerService.cloneRequest(run, groups,
                new ObjectMapper());

        assertThat(request.scenario()).isEqualTo("load");
        assertThat(request.name()).isEqualTo("基线 (copy)");
        assertThat(request.nodes()).containsExactly("app-1", "app-2");
        assertThat(request.params()).containsEntry("TRAFFIC_MODE", "target-instance");
        assertThat(request.config()).containsKey("jvm");
        assertThat(request.faultSchedule()).hasSize(1);
        assertThat(request.faultSchedule().get(0)).containsEntry("action", "freeze");
    }

    @Test
    void aLongNameIsTruncatedBeforeTheCopySuffix() {
        String longName = "x".repeat(130);
        RunRecord run = new RunRecord("run-1", "load", longName, null,
                RunStatus.DRAFT, "abc", "tester", NOW, NOW, null, null, false);

        LedgerViews.RunCreateRequest request = RunLedgerService.cloneRequest(run,
                Map.of("request", "{}"), new ObjectMapper());

        assertThat(request.name()).hasSize(128);
        assertThat(request.name()).endsWith(" (copy)");
        assertThat(request.nodes()).isEmpty();
        assertThat(request.config()).isNull();
    }

    @Test
    void anEmptyTemplateKeepsNullGroups() {
        RunRecord run = new RunRecord("run-1", "smoke", null, null,
                RunStatus.DRAFT, "abc", "tester", NOW, NOW, null, null, false);

        LedgerViews.RunCreateRequest request = RunLedgerService.cloneRequest(run,
                Map.of(), new ObjectMapper());

        assertThat(request.name()).isNull();
        assertThat(request.params()).isNull();
        assertThat(request.faultSchedule()).isNull();
    }
}
