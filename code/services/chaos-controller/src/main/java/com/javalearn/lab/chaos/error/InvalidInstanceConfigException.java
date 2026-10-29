package com.javalearn.lab.chaos.error;

/**
 * Stage2 B4-01: the per-instance configuration failed schema, whitelist or
 * resource-budget validation. The message lists the first actionable rule; it
 * never echoes a shell command because no shell string is ever built.
 */
public class InvalidInstanceConfigException extends RuntimeException {

    public InvalidInstanceConfigException(String message) {
        super(message);
    }
}
