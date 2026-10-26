package com.javalearn.lab.chaos.guide;

import java.util.Locale;

/** Lifecycle of the interactive guide (one instance per controller). */
public enum GuideStatus {

    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
