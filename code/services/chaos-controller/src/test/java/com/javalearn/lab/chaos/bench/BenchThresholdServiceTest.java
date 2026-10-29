package com.javalearn.lab.chaos.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.exec.BenchExecProperties;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.bench.exec.K6ContainerGateway;
import com.javalearn.lab.chaos.error.BenchReportNotFoundException;
import com.javalearn.lab.chaos.error.BenchReportTooLargeException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * B5-04b threshold parser. The fixtures mirror the real k6 1.3 summary shape
 * (metric.thresholds is {condition: crossed}); crossed=false means PASS.
 */
class BenchThresholdServiceTest {

    private static final String RUN_ID = "run-20261029-004";

    @TempDir
    Path reportsRoot;

    private final BenchExecProperties properties = new BenchExecProperties();
    private BenchThresholdService service;

    @BeforeEach
    void setUp() {
        properties.setReportsMount(reportsRoot.toString());
        BenchRunExecutor executor = new BenchRunExecutor(properties,
                mock(K6ContainerGateway.class));
        service = new BenchThresholdService(new ObjectMapper(), executor);
    }

    @Test
    void parsesTheLiveShapeAndTreatsCrossedFalseAsPass() throws Exception {
        write("summary-pass.json");

        BenchThresholdService.ThresholdReport report = service.thresholds(RUN_ID);

        assertThat(report.available()).isTrue();
        assertThat(report.note()).isNull();
        assertThat(report.thresholds()).hasSize(4);
        assertThat(report.thresholds()).allSatisfy(threshold -> assertThat(threshold.passed())
                .as(threshold.metric() + " " + threshold.condition()).isTrue());
        assertThat(report.thresholds()).extracting(BenchThresholdService.ThresholdResult::metric)
                .containsExactlyInAnyOrder("server_errors", "flow_errors",
                        "http_req_duration{flow:product}", "http_req_duration{flow:product}");
        assertThat(report.thresholds()).extracting(BenchThresholdService.ThresholdResult::condition)
                .contains("rate<0.01", "p(95)<300", "p(99)<800");
        assertThat(report.metrics().httpReqDuration().avg()).isEqualTo(24.074178640000003);
        assertThat(report.metrics().httpReqDuration().med()).isEqualTo(23.721716);
        assertThat(report.metrics().iterationDuration().avg()).isEqualTo(1028.976353);
    }

    @Test
    void crossedTrueIsAFailureAndUntestedMetricsStayNull() throws Exception {
        write("summary-fail.json");

        BenchThresholdService.ThresholdReport report = service.thresholds(RUN_ID);

        assertThat(report.thresholds()).hasSize(3);
        assertThat(report.thresholds()).filteredOn(t -> t.metric().equals("server_errors"))
                .singleElement().satisfies(t -> assertThat(t.passed()).isFalse());
        assertThat(report.thresholds()).filteredOn(t -> t.metric().equals("flow_errors"))
                .singleElement().satisfies(t -> assertThat(t.passed()).isFalse());
        assertThat(report.thresholds()).filteredOn(t -> t.metric().equals("checks"))
                .singleElement().satisfies(t -> assertThat(t.passed()).isTrue());
        // http_reqs carries no avg/med; the parser never invents 0.
        assertThat(report.metrics().httpReqDuration().avg()).isEqualTo(374.4022472857143);
        assertThat(report.metrics().iterationDuration().med()).isEqualTo(3626.686298);
    }

    @Test
    void aScenarioWithoutThresholdsSaysNoThresholdsInsteadOfPass() throws Exception {
        write("summary-no-thresholds.json");

        BenchThresholdService.ThresholdReport report = service.thresholds(RUN_ID);

        assertThat(report.thresholds()).isEmpty();
        assertThat(report.note()).isEqualTo("NO_THRESHOLDS");
        assertThat(report.metrics().httpReqDuration().med()).isNull();
        assertThat(report.metrics().iterationDuration().med()).isNull();
    }

    @Test
    void aMissingReportIs404AndAnOversizedReportIs413() throws Exception {
        assertThatThrownBy(() -> service.thresholds(RUN_ID))
                .isInstanceOf(BenchReportNotFoundException.class);

        write("summary-pass.json");
        properties.setReportMaxBytes(16L);
        assertThatThrownBy(() -> service.thresholds(RUN_ID))
                .isInstanceOf(BenchReportTooLargeException.class);
    }

    @Test
    void malformedJsonIsReportedInsteadOfParsedAsAnEmptyPass() throws Exception {
        Path dir = Files.createDirectories(reportsRoot.resolve(RUN_ID));
        Files.writeString(dir.resolve("summary.json"), "{not-json");

        assertThatThrownBy(() -> service.thresholds(RUN_ID))
                .isInstanceOf(BenchUnavailableException.class)
                .hasMessageContaining("not valid JSON");
    }

    private void write(String fixture) throws Exception {
        Path dir = Files.createDirectories(reportsRoot.resolve(RUN_ID));
        try (InputStream input = getClass().getResourceAsStream("/k6/" + fixture)) {
            assertThat(input).as("fixture %s", fixture).isNotNull();
            Files.copy(input, dir.resolve("summary.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
