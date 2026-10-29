package com.javalearn.lab.chaos.instance;

/**
 * Stage2 B4-03 GC-log readback. {@code status} is {@code OK}, {@code DISABLED}
 * (config has GC logging off), {@code NO_CONFIG}, {@code NO_DATA} (the file is
 * absent or unreadable) or {@code ERROR}; content is bounded by
 * {@code lab.instance.gc-log-max-bytes}.
 */
public record InstanceLogsView(String instance, String status, String path, int requestedLines,
                               boolean truncated, String content, String note) {
}
