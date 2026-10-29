package com.javalearn.lab.chaos.topology;

import java.util.List;

/**
 * REST payload for {@code GET /lab/topology} (design §9.5/§16.3/§16.10).
 *
 * <p>Every numeric metric is nullable on purpose: a missing series stays
 * {@code null} (the console renders "无数据/未接线"), never a synthetic zero.
 * {@code dataQuality} is one of {@code OK / PARTIAL / NO_DATA / UNWIRED}.</p>
 */
public final class TopologyViews {

    private TopologyViews() {
    }

    public record TopologyView(String generatedAt, List<String> enabledNodes,
                               boolean includeSidecars, List<TopologyNodeView> nodes,
                               List<TopologyEdgeView> edges) {
    }

    public record TopologyNodeView(String id, String service, String label, String tier,
                                   boolean enabled, boolean hidden, String health,
                                   String observability, String jdkVersion, String imageTag,
                                   String imageId, String dataQuality,
                                   TopologyMetricsView metrics, List<String> notes) {
    }

    /**
     * Generic node metric surface. App nodes fill CPU/heap/threads/pool/HTTP;
     * redis fills {@code memUsedBytes} from {@code redis_memory_used_bytes};
     * mysql/nginx/rabbit expose their single reachability/count series through
     * {@code notes} because the contract has no dedicated field for them.
     */
    public record TopologyMetricsView(Double cpuPercent, Double heapUsedBytes,
                                      Double heapLimitBytes, Double memUsedBytes,
                                      Double memLimitBytes, Double threads,
                                      Double activeThreads, Double poolCore, Double poolMax,
                                      Double poolQueue, Double poolRejected,
                                      Double requestsPerSecond, Double avgResponseMs,
                                      Double p95Ms, Long updatedAtEpochSeconds) {

        public static TopologyMetricsView empty(Long updatedAtEpochSeconds) {
            return new TopologyMetricsView(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, updatedAtEpochSeconds);
        }
    }

    public record TopologyEdgeView(String from, String to, String kind) {
    }
}
