package com.javalearn.lab.chaos.precheck;

/** Overall verdict of one pre-check report. */
public enum PrecheckVerdict {
    /** Every item passed. */
    PASS,
    /** No blocking item, at least one warning. */
    WARN,
    /** At least one blocking item: the start is refused. */
    BLOCKED
}
