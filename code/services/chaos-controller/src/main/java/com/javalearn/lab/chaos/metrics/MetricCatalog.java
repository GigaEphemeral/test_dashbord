package com.javalearn.lab.chaos.metrics;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned metric catalog. The resource is the single fact source for the keys
 * a caller may ask for; PromQL never crosses the HTTP boundary as input.
 *
 * <p>Validation runs at construction (i.e. at startup when wired by Spring):
 * keys must match {@code [a-z0-9_.-]+} and be unique, PromQL/label/unit must be
 * non-blank, only the {@code ${range}} placeholder is allowed, and the catalog
 * may not contain more entries than {@code lab.metrics.max-keys}.</p>
 */
public final class MetricCatalog {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");

    private final int version;
    private final List<MetricDefinition> definitions;
    private final Map<String, MetricDefinition> byKey;

    /** Direct construction for tests and tools; performs the same validation. */
    public MetricCatalog(int version, List<MetricDefinition> definitions, int maxKeys) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalStateException("metrics catalog must contain at least one metric");
        }
        if (definitions.size() > maxKeys) {
            throw new IllegalStateException("metrics catalog has " + definitions.size()
                    + " entries but lab.metrics.max-keys is " + maxKeys
                    + "; raise LAB_METRICS_MAX_KEYS or trim the catalog");
        }
        Map<String, MetricDefinition> map = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MetricDefinition definition : definitions) {
            validateDefinition(definition);
            if (!seen.add(definition.key())) {
                throw new IllegalStateException("metrics catalog duplicate key '" + definition.key() + "'");
            }
            map.put(definition.key(), definition);
        }
        this.version = version;
        this.definitions = List.copyOf(definitions);
        this.byKey = Collections.unmodifiableMap(map);
    }

    /** Loads and validates the configured {@code classpath:}/ {@code file:} resource. */
    public static MetricCatalog load(ResourceLoader resourceLoader, String location, int maxKeys) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("lab.metrics.catalog-location not found: " + location
                    + "; env LAB_METRICS_CATALOG_LOCATION");
        }
        try (InputStream input = resource.getInputStream()) {
            Object root = new Yaml().load(input);
            if (!(root instanceof Map<?, ?> map)) {
                throw new IllegalStateException("metrics catalog " + location
                        + " must be a YAML mapping with a 'metrics' list");
            }
            int version = intValue(map.get("version"), 1, location, "version");
            Object entries = map.get("metrics");
            if (!(entries instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalStateException("metrics catalog " + location
                        + " must contain a non-empty 'metrics' list");
            }
            List<MetricDefinition> definitions = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    throw new IllegalStateException("metrics catalog " + location
                            + " has a non-mapping entry in 'metrics'");
                }
                definitions.add(new MetricDefinition(
                        text(entry.get("key"), location, "key"),
                        text(entry.get("label"), location, "label"),
                        text(entry.get("unit"), location, "unit"),
                        text(entry.get("promql"), location, "promql"),
                        booleanValue(entry.get("groupByInstance"))));
            }
            return new MetricCatalog(version, definitions, maxKeys);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read metrics catalog " + location + ": " + e, e);
        }
    }

    private static void validateDefinition(MetricDefinition definition) {
        if (definition.key() == null || !KEY.matcher(definition.key()).matches()) {
            throw new IllegalStateException("metrics catalog key '" + definition.key()
                    + "' must match [a-z0-9_.-]+");
        }
        requireText(definition.label(), "label", definition.key());
        requireText(definition.unit(), "unit", definition.key());
        requireText(definition.promql(), "promql", definition.key());
        Matcher matcher = PLACEHOLDER.matcher(definition.promql());
        while (matcher.find()) {
            if (!"${range}".equals(matcher.group())) {
                throw new IllegalStateException("metrics catalog key '" + definition.key()
                        + "' uses unsupported placeholder " + matcher.group()
                        + "; only ${range} is allowed");
            }
        }
    }

    private static void requireText(String value, String field, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("metrics catalog key '" + key + "' has a blank " + field);
        }
    }

    private static String text(Object value, String location, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalStateException("metrics catalog " + location + " field '" + field
                    + "' must be a string");
        }
        return text;
    }

    private static int intValue(Object value, int fallback, String location, String field) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("metrics catalog " + location + " field '" + field
                    + "' must be an integer (was '" + value + "')");
        }
    }

    private static boolean booleanValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    public int version() {
        return version;
    }

    public List<MetricDefinition> definitions() {
        return definitions;
    }

    public MetricDefinition get(String key) {
        return byKey.get(key);
    }

    public Set<String> keys() {
        return byKey.keySet();
    }
}
