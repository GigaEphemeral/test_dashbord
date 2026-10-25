package com.javalearn.lab.observability;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @RestController
    static class ProductController {

        @GetMapping("/products/{id}")
        Map<String, String> getProduct(@PathVariable("id") String id) {
            return Map.of("id", id, "name", "demo-product");
        }
    }
}
