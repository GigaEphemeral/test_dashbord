package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.error.InvalidTailException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.common.Result;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class LabController {

    static final int DEFAULT_TAIL = 200;
    static final int MAX_TAIL = 1000;

    private final ContainerGateway containerGateway;

    public LabController(ContainerGateway containerGateway) {
        this.containerGateway = containerGateway;
    }

    @GetMapping("/containers")
    public Result<List<ContainerView>> containers() {
        return Result.success(containerGateway.listLabContainers());
    }

    @GetMapping(value = "/logs/{container}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> logs(@PathVariable String container,
                                       @RequestParam(name = "tail", required = false) Integer tail) {
        int effectiveTail = normalizeTail(tail);
        String logs = containerGateway.tailLogs(container, effectiveTail);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(logs == null ? "" : logs);
    }

    static int normalizeTail(Integer tail) {
        if (tail == null) {
            return DEFAULT_TAIL;
        }
        if (tail < 1) {
            throw new InvalidTailException("tail must be between 1 and " + MAX_TAIL);
        }
        return Math.min(tail, MAX_TAIL);
    }
}
