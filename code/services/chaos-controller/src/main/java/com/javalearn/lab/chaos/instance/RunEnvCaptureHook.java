package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStartedHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B5: capture the run's environment identity at READY→RUNNING, so the exported
 * report can name the image/java/GC/options that were actually live. This is a
 * best-effort {@link RunStartedHook}: a missing container, unreadable readback
 * or ledger hiccup is logged and skipped - a run is never failed for it.
 */
public class RunEnvCaptureHook implements RunStartedHook {

    private static final Logger log = LoggerFactory.getLogger(RunEnvCaptureHook.class);

    private final InstanceConfigService configs;
    private final InstanceConfigProperties properties;
    private final InstanceConfigRepository storedConfigs;
    private final RunLedgerRepository ledger;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RunEnvCaptureHook(InstanceConfigService configs, InstanceConfigProperties properties,
                             InstanceConfigRepository storedConfigs, RunLedgerRepository ledger,
                             ObjectMapper objectMapper, Clock clock) {
        this.configs = configs;
        this.properties = properties;
        this.storedConfigs = storedConfigs;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void onStarted(String runId) {
        try {
            Optional<RunRecord> stored = ledger.findRun(runId);
            if (stored.isEmpty()) {
                return;
            }
            List<String> nodes = stored.get().nodeList().isEmpty()
                    ? properties.getNames().stream()
                            .filter(name -> !name.equals(properties.getJdk8Instance()))
                            .toList()
                    : stored.get().nodeList();
            for (String node : nodes) {
                capture(runId, node);
            }
        } catch (RuntimeException e) {
            log.warn("run env snapshot failed for {}: {}", runId, e.toString());
        }
    }

    private void capture(String runId, String node) {
        if (!properties.isKnownInstance(node)) {
            return;
        }
        try {
            InstanceViews.EffectiveInstanceView live = configs.effective(node);
            if (live == null || (live.imageTag() == null && live.imageId() == null)) {
                return;
            }
            InstanceConfig config = storedConfigs.find(node)
                    .map(row -> parse(row.configJson()))
                    .orElse(null);
            String container = live.containerName() != null
                    ? live.containerName() : properties.containerName(node);
            ledger.insertEnv(runId, container, live.imageTag(), live.imageId(),
                    config == null || config.jdkVersion() == null
                            ? null : String.valueOf(config.jdkVersion()),
                    json(jvmArgs(live)), json(gcFields(config)), clock.instant());
        } catch (RuntimeException e) {
            log.debug("run env snapshot skipped {}: {}", node, e.toString());
        }
    }

    private InstanceConfig parse(String json) {
        try {
            return objectMapper.readValue(json, InstanceConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> jvmArgs(InstanceViews.EffectiveInstanceView live) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("javaToolOptions", live.javaToolOptions());
        args.put("jvmReadback", live.jvmReadback());
        if (live.jvmFlags() != null) {
            args.put("jvmFlags", live.jvmFlags());
        }
        return args;
    }

    private static Map<String, Object> gcFields(InstanceConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("collector", config.gcCollector());
        gc.put("maxGcPauseMillis", config.maxGcPauseMillis());
        gc.put("g1NewSizePercent", config.g1NewSizePercent());
        gc.put("g1MaxNewSizePercent", config.g1MaxNewSizePercent());
        gc.put("g1HeapRegionSize", config.g1HeapRegionSize());
        gc.put("initiatingHeapOccupancyPercent", config.initiatingHeapOccupancyPercent());
        gc.put("gcLogEnabled", config.gcLogEnabled());
        return gc;
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
