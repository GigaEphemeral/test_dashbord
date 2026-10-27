package com.javalearn.lab.seckill.jdk8.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;

/**
 * Externalized configuration of the JDK 8 snapshot bundle (design §9.3).
 *
 * <p>Bound from {@code app.lab.snapshot.*}. The class declares no Java defaults:
 * {@code application.yml} is the single source of defaults, every path, timeout
 * and retention budget is a {@code ${ENV:default}} placeholder, and
 * {@link #validate()} fails startup on a value that could only produce a silent
 * mis-capture (blank tool path, zero timeout, unbounded retention).</p>
 *
 * <p>{@code jmap-mode} exists because {@code -histo:live} forces a full GC before
 * it counts: it changes the very heap the snapshot is meant to observe. The
 * default is therefore the non-live {@code -histo}; {@code histo-live} is an
 * explicit, documented opt-in for "is this object really live?" investigations.</p>
 */
@ConfigurationProperties(prefix = "app.lab.snapshot")
public class SnapshotProperties {

    /** Values accepted by {@link #jmapMode}. */
    public static final String JMAP_MODE_HISTO = "histo";
    public static final String JMAP_MODE_HISTO_LIVE = "histo-live";

    /** Master switch; when false both endpoints still answer but write nothing. */
    private boolean enabled;
    /** Root directory of the timestamped bundles (container path of the host mount). */
    private String rootDir;
    /** Maximum number of bundles kept (newest win). */
    private long maxCount;
    /** Bundles older than this are pruned; 0 disables age-based pruning. */
    private long maxAgeSeconds;
    /** Per-command wall-clock budget for one jstack/jmap invocation. */
    private long commandTimeoutMs;
    /** {@code histo} (no GC, default) or {@code histo-live} (full GC first). */
    private String jmapMode;
    /** jstack executable: "jstack" on PATH of the JDK image, or an absolute path. */
    private String jstackPath;
    /** jmap executable: "jmap" on PATH of the JDK image, or an absolute path. */
    private String jmapPath;
    /**
     * jcmd executable, used only for {@code GC.heap_info}: JDK 8 removed
     * {@code jmap -heap}, and jcmd is the one JDK 8 tool that still prints the
     * heap geometry (same diagnostic the JDK 21 bundle uses).
     */
    private String jcmdPath;
    /**
     * The analysis template copied into every bundle. Points at the repository
     * file {@code doc/process/Stage1/snapshot-analysis-template.md} (reused, not forked);
     * compose bind-mounts that file into the container.
     */
    private String templatePath;

    private final Annotation annotation = new Annotation();

    @PostConstruct
    void validate() {
        requirePositive(commandTimeoutMs, "app.lab.snapshot.command-timeout-ms");
        requireAtLeast(maxCount, 1L, "app.lab.snapshot.max-count");
        requireNonNegative(maxAgeSeconds, "app.lab.snapshot.max-age-seconds");
        if (enabled) {
            requireText(rootDir, "app.lab.snapshot.root-dir");
            requireText(jstackPath, "app.lab.snapshot.jstack-path");
            requireText(jmapPath, "app.lab.snapshot.jmap-path");
            requireText(jcmdPath, "app.lab.snapshot.jcmd-path");
            requireText(templatePath, "app.lab.snapshot.template-path");
        }
        if (jmapMode == null || (!JMAP_MODE_HISTO.equals(jmapMode)
                && !JMAP_MODE_HISTO_LIVE.equals(jmapMode))) {
            throw new IllegalStateException("Configuration must be one of "
                    + JMAP_MODE_HISTO + "/" + JMAP_MODE_HISTO_LIVE + ": app.lab.snapshot.jmap-mode");
        }
        requirePositive(annotation.connectTimeoutMs,
                "app.lab.snapshot.annotation.connect-timeout-ms");
        requireAtMost(annotation.connectTimeoutMs, Integer.MAX_VALUE,
                "app.lab.snapshot.annotation.connect-timeout-ms");
        requirePositive(annotation.readTimeoutMs,
                "app.lab.snapshot.annotation.read-timeout-ms");
        requireAtMost(annotation.readTimeoutMs, Integer.MAX_VALUE,
                "app.lab.snapshot.annotation.read-timeout-ms");
        // An annotation without a URL is a supported OFF state (skip, count it),
        // not a misconfiguration; a supplied URL must be an absolute http(s) base.
        annotation.buildAnnotationUri("app.lab.snapshot.annotation.url");
    }

    /** True when the configured mode asks jmap to run a full GC before counting. */
    public boolean isLiveHistogram() {
        return JMAP_MODE_HISTO_LIVE.equals(jmapMode);
    }

    private static void requireText(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Configuration must not be blank: " + key);
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0L) {
            throw new IllegalStateException("Configuration must be positive: " + key);
        }
    }

    private static void requireAtLeast(long value, long min, String key) {
        if (value < min) {
            throw new IllegalStateException(
                    "Configuration must be at least " + min + ": " + key);
        }
    }

    private static void requireNonNegative(long value, String key) {
        if (value < 0L) {
            throw new IllegalStateException("Configuration must not be negative: " + key);
        }
    }

    private static void requireAtMost(long value, long max, String key) {
        if (value > max) {
            throw new IllegalStateException(
                    "Configuration must be at most " + max + ": " + key);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public long getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(long maxCount) {
        this.maxCount = maxCount;
    }

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    public void setMaxAgeSeconds(long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public String getJmapMode() {
        return jmapMode;
    }

    public void setJmapMode(String jmapMode) {
        this.jmapMode = jmapMode;
    }

    public String getJstackPath() {
        return jstackPath;
    }

    public void setJstackPath(String jstackPath) {
        this.jstackPath = jstackPath;
    }

    public String getJmapPath() {
        return jmapPath;
    }

    public void setJmapPath(String jmapPath) {
        this.jmapPath = jmapPath;
    }

    public String getJcmdPath() {
        return jcmdPath;
    }

    public void setJcmdPath(String jcmdPath) {
        this.jcmdPath = jcmdPath;
    }

    public String getTemplatePath() {
        return templatePath;
    }

    public void setTemplatePath(String templatePath) {
        this.templatePath = templatePath;
    }

    public Annotation getAnnotation() {
        return annotation;
    }

    /**
     * Best-effort Grafana annotation of a successful snapshot (design §9.3).
     * A blank {@code url} means OFF: the bundle is still written and the skip is
     * counted. Credentials come from the environment and are never logged.
     */
    public static class Annotation {

        /** Switch for the annotation call only; capture is governed by {@code enabled}. */
        private boolean enabled;
        /** Grafana base URL; blank means "do not annotate". */
        private String url;
        private String user;
        private String password;
        /** TCP connect budget for the annotation POST. */
        private long connectTimeoutMs;
        /** Response read budget for the annotation POST. */
        private long readTimeoutMs;
        /** Extra tags appended to the mandatory snapshot/thread-lab/instance set. */
        private String tags;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public String getTags() {
            return tags;
        }

        public void setTags(String tags) {
            this.tags = tags;
        }

        /**
         * Builds {@code <base>/api/annotations}; returns {@code null} for a blank
         * base URL (annotation OFF). A supplied URL must be absolute http(s) with
         * a host, otherwise startup fails instead of silently never annotating.
         */
        public java.net.URI buildAnnotationUri(String key) {
            if (url == null || url.trim().isEmpty()) {
                return null;
            }
            String trimmed = url.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            java.net.URI uri;
            try {
                uri = java.net.URI.create(trimmed + "/api/annotations");
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        key + " is not a valid URL: " + invalid.getMessage(), invalid);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalStateException(key + " must be an absolute http(s) URL: " + url);
            }
            if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                throw new IllegalStateException(key + " must carry a host: " + url);
            }
            return uri;
        }
    }
}
