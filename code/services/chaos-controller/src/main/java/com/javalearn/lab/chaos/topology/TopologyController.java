package com.javalearn.lab.chaos.topology;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage2 B5-01a topology contract consumed by the console heat view.
 *
 * <pre>
 * GET /lab/topology?includeSidecars=false&amp;enabled=app-1,app-2
 * </pre>
 *
 * <p>{@code enabled} is optional: when omitted the service uses the newest
 * ledger run with a non-null {@code nodes} selection, else every configured app
 * name. All paths stay behind the existing {@code TokenFilter}; the controller
 * route itself is exposed through nginx as {@code /lab-api/lab/topology}.</p>
 */
@RestController
public class TopologyController {

    private final TopologyService service;

    public TopologyController(TopologyService service) {
        this.service = service;
    }

    @GetMapping("/lab/topology")
    public Result<TopologyView> topology(
            @RequestParam(name = "includeSidecars", defaultValue = "false")
            boolean includeSidecars,
            @RequestParam(name = "enabled", required = false) String enabled) {
        return Result.success(service.snapshot(enabled, includeSidecars));
    }
}
