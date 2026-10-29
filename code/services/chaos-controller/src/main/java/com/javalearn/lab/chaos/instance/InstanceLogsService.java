package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerReadiness;
import com.javalearn.lab.chaos.model.ExecResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Optional;

/**
 * Stage2 B4-03: read the GC log of one running instance without mounting the
 * log directory into the controller. The file is written under the app's
 * writable snapshot bind and read with a bounded {@code tail} exec.
 */
public class InstanceLogsService {

    public static final String OK = "OK";
    public static final String DISABLED = "DISABLED";
    public static final String NO_CONFIG = "NO_CONFIG";
    public static final String NO_DATA = "NO_DATA";
    public static final String ERROR = "ERROR";

    private final InstanceConfigProperties properties;
    private final InstanceConfigRepository repository;
    private final ObjectProvider<ContainerGateway> containerGatewayProvider;
    private final LedgerProperties ledgerProperties;
    private final LedgerBootstrap bootstrap;
    private final ObjectMapper objectMapper;

    public InstanceLogsService(InstanceConfigProperties properties,
                               InstanceConfigRepository repository,
                               ObjectProvider<ContainerGateway> containerGatewayProvider,
                               LedgerProperties ledgerProperties, LedgerBootstrap bootstrap,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.repository = repository;
        this.containerGatewayProvider = containerGatewayProvider;
        this.ledgerProperties = ledgerProperties;
        this.bootstrap = bootstrap;
        this.objectMapper = objectMapper;
    }

    public InstanceLogsView gcLog(String instance, Integer requestedLines) {
        if (!properties.isKnownInstance(instance)) {
            throw new com.javalearn.lab.chaos.error.InstanceNotFoundException(
                    "unknown instance '" + instance + "'; configured: " + properties.getNames());
        }
        LedgerReadiness.requireReady(ledgerProperties, bootstrap);
        StoredInstanceConfig stored;
        try {
            stored = repository.find(instance).orElse(null);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "instance log read");
        }
        if (stored == null) {
            return new InstanceLogsView(instance, NO_CONFIG, null, 0, false, null,
                    "no configuration saved for this instance");
        }
        JsonNode config;
        try {
            config = objectMapper.readTree(stored.configJson());
        } catch (Exception e) {
            throw new InvalidInstanceConfigException("stored config is not valid JSON: "
                    + e.getMessage());
        }
        boolean enabled = config.path("gcLogEnabled").asBoolean(false);
        String path = config.path("gcLogPath").asText(null);
        if (!enabled || path == null || path.isBlank()) {
            return new InstanceLogsView(instance, DISABLED, path, 0, false, null,
                    "gcLogEnabled is false for this instance");
        }
        String normalized = path.trim();
        String root = properties.getGcLogRoot();
        // Same allow-list logic as the validator: a "/" root means "any absolute
        // path", otherwise the path must live strictly under the root.
        String prefix = "/".equals(root) ? "/" : root + "/";
        if (!normalized.startsWith(prefix) || normalized.contains("..")
                || normalized.equals(prefix) || normalized.isBlank()) {
            throw new InvalidInstanceConfigException("gcLogPath '" + normalized
                    + "' is outside lab.instance.gc-log-root " + root);
        }
        int lines = requestedLines == null ? properties.getGcLogDefaultLines() : requestedLines;
        if (lines < 1 || lines > properties.getGcLogMaxLines()) {
            throw new InvalidInstanceConfigException("lines must be between 1 and "
                    + properties.getGcLogMaxLines() + " (was " + lines + ")");
        }
        ContainerGateway gateway = containerGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new DockerUnavailableException("Docker container gateway is not available");
        }
        String container = properties.containerName(instance);
        int tailTimeoutSeconds = properties.getGcLogTailTimeoutSeconds();
        ExecResult result;
        String readPath = normalized;
        try {
            result = tail(gateway, container, normalized, lines, tailTimeoutSeconds);
            if (!result.succeeded()) {
                // JDK 8 -XX:+UseGCLogFileRotation writes <path>.0.current (and
                // .1.current, ...) instead of the plain file; try the bounded
                // rotation candidates before declaring NO_DATA.
                int count = Math.max(1, Math.min(20, config.path("gcLogFileCount").asInt(10)));
                for (int i = 0; i < count && !result.succeeded(); i++) {
                    String candidate = normalized + "." + i + ".current";
                    ExecResult next = tail(gateway, container, candidate, lines,
                            tailTimeoutSeconds);
                    if (next.succeeded()) {
                        result = next;
                        readPath = candidate;
                    }
                }
            }
        } catch (RuntimeException e) {
            return new InstanceLogsView(instance, NO_DATA, normalized, lines, false, null,
                    "cannot read " + normalized + " from " + container + ": " + e.getMessage());
        }
        if (!result.succeeded()) {
            return new InstanceLogsView(instance, NO_DATA, normalized, lines, false, null,
                    "tail " + normalized + " failed (exit=" + result.exitCode() + "): "
                            + truncate(result.stderr(), 500));
        }
        String text = result.stdout() == null ? "" : result.stdout();
        if (text.isBlank()) {
            return new InstanceLogsView(instance, NO_DATA, readPath, lines, false, null,
                    "log file exists but is empty (GC may not have run yet)");
        }
        int max = properties.getGcLogMaxBytes();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean truncated = bytes.length > max;
        if (truncated) {
            // Keep the tail end, which holds the most recent GC events, and stay
            // inside the byte budget: start after any split UTF-8 lead byte.
            int start = Math.max(0, bytes.length - max);
            while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
                start++;
            }
            text = new String(bytes, start, bytes.length - start,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return new InstanceLogsView(instance, OK, readPath, lines, truncated, text, null);
    }

    private static ExecResult tail(ContainerGateway gateway, String container, String path,
                                   int lines, int timeoutSeconds) {
        return gateway.execInContainer(container,
                List.of("tail", "-n", String.valueOf(lines), path),
                List.of(), null, timeoutSeconds);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
