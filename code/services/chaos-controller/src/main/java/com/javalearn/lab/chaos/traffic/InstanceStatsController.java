package com.javalearn.lab.chaos.traffic;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * B5-02 read-only split statistics; behind the same {@code TokenFilter} as the
 * rest of {@code /lab/**}. No PromQL is accepted from the caller.
 */
@RestController
public class InstanceStatsController {

    private final InstanceStatsService service;

    public InstanceStatsController(InstanceStatsService service) {
        this.service = service;
    }

    @GetMapping("/lab/runs/{runId}/instance-stats")
    public Result<InstanceStatsViews.InstanceStatsView> stats(@PathVariable String runId) {
        return Result.success(service.query(runId));
    }
}
