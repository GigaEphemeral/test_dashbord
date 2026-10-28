package com.javalearn.lab.chaos.metrics;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;

/**
 * Read-only metrics endpoints behind the existing {@code TokenFilter}.
 *
 * <p>There is deliberately no arbitrary-PromQL endpoint: {@code keys} selects
 * entries from the versioned catalog only. {@code from}/{@code to} accept
 * ISO-8601 or epoch seconds/millis (values >= 1e11 are treated as millis).</p>
 */
@RestController
public class MetricsController {

    private final MetricsService service;

    public MetricsController(MetricsService service) {
        this.service = service;
    }

    /** Run context; without from/to the window is start-grace .. end+grace. */
    @GetMapping("/lab/runs/{runId}/metrics")
    public Result<MetricsQueryView> runMetrics(
            @PathVariable String runId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "keys", required = false) String keys,
            @RequestParam(name = "phase", required = false) String phase) {
        return Result.success(service.queryRun(runId, from, to, keys, phase));
    }

    /** Realtime view without a run context; from and to are required. */
    @GetMapping("/lab/metrics/window")
    public Result<MetricsQueryView> window(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "keys", required = false) String keys,
            @RequestParam(name = "phase", required = false) String phase) {
        return Result.success(service.queryWindow(from, to, keys, phase));
    }
}
