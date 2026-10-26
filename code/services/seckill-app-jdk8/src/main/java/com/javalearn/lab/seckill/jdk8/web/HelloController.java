package com.javalearn.lab.seckill.jdk8.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same path and body as the JDK 21 shell's HelloController. Note: in Prometheus
 * the scraped {@code instance} label is the target address (the application's
 * instance tag is overridden at scrape time unless honor_labels is set), so the
 * common {@code app} tag — not {@code instance} — is what tells the two
 * runtimes apart on one dashboard.
 */
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

    public static class HelloResponse {

        private final String message;
        private final String instance;

        public HelloResponse(String message, String instance) {
            this.message = message;
            this.instance = instance;
        }

        public String getMessage() {
            return message;
        }

        public String getInstance() {
            return instance;
        }
    }
}
