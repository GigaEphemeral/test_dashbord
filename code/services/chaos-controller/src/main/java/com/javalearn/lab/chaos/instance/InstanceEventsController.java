package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage2 B5-04b read-only diagnostics: OOM / heap-dump events scanned from a
 * bounded container log tail. Token-guarded like every {@code /lab/**} path.
 */
@RestController
public class InstanceEventsController {

    private final InstanceEventsService service;

    public InstanceEventsController(InstanceEventsService service) {
        this.service = service;
    }

    @GetMapping("/lab/instances/{instance}/events")
    public Result<InstanceViews.InstanceEventsView> events(@PathVariable String instance) {
        return Result.success(service.events(instance));
    }
}
