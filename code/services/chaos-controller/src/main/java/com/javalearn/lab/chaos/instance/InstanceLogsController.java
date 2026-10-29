package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stage2 B4-03 GC-log readback; token-guarded like every /lab/** endpoint. */
@RestController
public class InstanceLogsController {

    private final InstanceLogsService service;

    public InstanceLogsController(InstanceLogsService service) {
        this.service = service;
    }

    @GetMapping("/lab/instances/{instance}/logs/gc")
    public Result<InstanceLogsView> gcLog(@PathVariable String instance,
                                          @RequestParam(name = "lines", required = false)
                                          Integer lines) {
        return Result.success(service.gcLog(instance, lines));
    }
}
