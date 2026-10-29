package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/**
 * Stage2 B4-01 per-instance configuration (design §4.3/§4.4/§16.4–§16.7).
 *
 * <p>Every component is nullable on purpose: {@code null} means "JVM default /
 * not configured" and the validator never invents a value for it. The only
 * required component is {@link #jdkVersion} (8 or 21). {@code 0} is a legal
 * "disabled" value only for the Caffeine expiry/refresh fields; GC logging is
 * disabled through {@link #gcLogEnabled}.</p>
 *
 * <p>The record is stored as JSON in {@code lab_instance_config}; the
 * normalized copy (not the raw request) is what gets persisted.</p>
 */
public record InstanceConfig(
        // runtime / heap
        Integer jdkVersion,
        String imageTag,
        Integer xmsMb,
        Integer xmxMb,
        Integer metaspaceMb,
        Integer directMemoryMb,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> extraJvmArgs,
        // GC
        String gcCollector,
        Integer maxGcPauseMillis,
        Integer g1NewSizePercent,
        Integer g1MaxNewSizePercent,
        String g1HeapRegionSize,
        Integer initiatingHeapOccupancyPercent,
        Integer parallelGcThreads,
        Integer newRatio,
        Integer survivorRatio,
        // business thread pool
        Integer corePoolSize,
        Integer maxPoolSize,
        Integer queueCapacity,
        Integer keepAliveSeconds,
        String rejectionPolicy,
        // Caffeine
        Boolean caffeineEnabled,
        Long maximumSize,
        Long expireAfterWriteSeconds,
        Long expireAfterAccessSeconds,
        Long refreshAfterWriteSeconds,
        Boolean recordStats,
        // GC log / diagnostics
        Boolean gcLogEnabled,
        String gcLogPath,
        Integer gcLogFileSizeMb,
        Integer gcLogFileCount,
        Boolean heapDumpOnOutOfMemory,
        String heapDumpPath,
        // Tomcat (stored for display only in B4-01)
        Integer tomcatMaxThreads,
        Integer acceptCount,
        Integer minSpareThreads) {
}
