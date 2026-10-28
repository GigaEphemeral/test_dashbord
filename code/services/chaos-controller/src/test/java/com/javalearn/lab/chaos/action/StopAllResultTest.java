package com.javalearn.lab.chaos.action;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B2-04b1 keeps every pre-existing {@link StopAllResult} shape working. */
class StopAllResultTest {

    @Test
    void thePreNetworkConstructorStillWorks() {
        StopAllResult result = new StopAllResult(2, 1, 0);

        assertThat(result.toxiproxy().status()).isEqualTo(NetworkResetReport.STATUS_NOT_CONFIGURED);
        assertThat(result.operationId()).isNull();
        assertThat(result.traffic()).isNull();
        assertThat(result.threads()).isEmpty();
        assertThat(result.k6Stopped()).isZero();
        assertThat(result.k6Cleaned()).isZero();
        assertThat(result.k6Failed()).isZero();
    }

    @Test
    void thePreB102ConstructorPreservesTheNetworkReportThroughWithOperationId() {
        NetworkResetReport network = new NetworkResetReport(NetworkResetReport.STATUS_RESET, 1, 1,
                List.of());

        StopAllResult result = new StopAllResult(1, 0, 0, network).withOperationId("op-1");

        assertThat(result.reverted()).isEqualTo(1);
        assertThat(result.toxiproxy()).isEqualTo(network);
        assertThat(result.operationId()).isEqualTo("op-1");
        assertThat(result.traffic()).isNull();
        assertThat(result.threads()).isEmpty();
    }

    @Test
    void withOperationIdKeepsTheEnvironmentOutcomes() {
        StopAllResult merged = new StopAllResult(2, 0, 1,
                new NetworkResetReport(NetworkResetReport.STATUS_RESET, 2, 2, List.of()))
                .withEnvironment(StopAllResult.TRAFFIC_STOPPED,
                        Map.of("app-1", "ok", "app-2", "failed"), 1, 2, 0);

        StopAllResult result = merged.withOperationId("op-9");

        assertThat(result.operationId()).isEqualTo("op-9");
        assertThat(result.reverted()).isEqualTo(2);
        assertThat(result.pending()).isEqualTo(1);
        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_STOPPED);
        assertThat(result.threads()).containsEntry("app-2", "failed");
        assertThat(result.k6Stopped()).isEqualTo(1);
        assertThat(result.k6Cleaned()).isEqualTo(2);
        assertThat(result.k6Failed()).isZero();
    }

    @Test
    void theThreadMapIsACopyAndCannotBeMutatedByCallers() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("app-1", "ok");

        StopAllResult result = new StopAllResult(0, 0, 0, null, null,
                StopAllResult.TRAFFIC_NOT_RUNNING, source, 0, 0, 0);
        source.put("app-2", "failed");

        assertThat(result.threads()).containsOnlyKeys("app-1");
        assertThatThrownBy(() -> result.threads().put("app-3", "ok"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
