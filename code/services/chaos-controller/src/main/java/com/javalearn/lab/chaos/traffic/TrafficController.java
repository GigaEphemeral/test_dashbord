package com.javalearn.lab.chaos.traffic;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Background traffic API for the lab console. Under {@code /lab/**} it is
 * covered by the shared {@code TokenFilter}.
 */
@RestController
@RequestMapping("/lab/traffic")
public class TrafficController {

    private final TrafficService trafficService;

    public TrafficController(TrafficService trafficService) {
        this.trafficService = trafficService;
    }

    @GetMapping("/state")
    public Result<TrafficStateView> state() {
        return Result.success(trafficService.state());
    }

    @PostMapping("/start")
    public Result<TrafficStateView> start() {
        return Result.success(trafficService.start());
    }

    @PostMapping("/stop")
    public Result<TrafficStateView> stop() {
        return Result.success(trafficService.stop());
    }
}
