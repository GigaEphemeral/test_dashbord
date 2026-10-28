package com.javalearn.lab.chaos.precheck;

/**
 * Result of one pre-check item. {@link #BLOCK} refuses the start, {@link #WARN}
 * is reported but does not block, {@link #PASS} is clean.
 */
public enum PrecheckStatus {
    PASS,
    WARN,
    BLOCK
}
