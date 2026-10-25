package com.javalearn.lab.seckill.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private final String instance;

    public HelloController(@Value("${observability.instance:unknown}") String instance) {
        this.instance = instance;
    }

    @GetMapping("/api/hello")
    public HelloResponse hello() {
        return new HelloResponse("hello", instance);
    }

    public record HelloResponse(String message, String instance) {
    }
}
