package com.javalearn.lab.seckill.jdk8;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EOL teaching shell (design 2026-10-26-ha-concurrency-lab-design.md section 10):
 * Spring Boot 2.7.18 on Java 8, the JDK 8 side of the D7 dual-runtime comparison.
 * Java 8 syntax only, hot read path only; must never be used for real projects.
 */
@SpringBootApplication
public class SeckillJdk8Application {

    public static void main(String[] args) {
        SpringApplication.run(SeckillJdk8Application.class, args);
    }
}
