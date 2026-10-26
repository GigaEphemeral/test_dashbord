package com.javalearn.lab.seckill.datasource;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * The single mode switch for the MySQL access path (design §3.3/§8.3).
 *
 * <p>Mirrors {@code RedisAccessProperties} and its {@code REDIS_MODE}
 * precedent: the dev profile uses the single-node MySQL, the db-ha tier uses
 * the InnoDB Cluster behind MySQL Router, and the switch is an environment
 * variable ({@code MYSQL_MODE}), never a code branch or a different image.</p>
 *
 * <ul>
 *   <li>{@code standalone} (default): both Hikari pools keep pointing at the
 *       single-node dev MySQL exactly as before; the Router URLs are ignored.</li>
 *   <li>{@code router}: the write pool uses {@code DB_WRITE_URL} (Router 6446,
 *       read-write, follows the current primary) and the read pool uses
 *       {@code DB_READ_URL} (Router 6447, read-only, load-balanced over the
 *       secondaries). Both are REQUIRED — a missing value fails startup instead
 *       of silently falling back to the dev MySQL, exactly like
 *       {@code REDIS_MODE=sentinel} never falls back to the standalone host.</li>
 * </ul>
 *
 * <p>The mode, not the presence of the Router keys, is the switch: setting
 * {@code DB_*_URL} without {@code MYSQL_MODE=router} must not half-switch one
 * pool, which is the confusing state the lab repeatedly warns about.</p>
 */
@ConfigurationProperties(prefix = "app.datasource")
public class DataSourceAccessProperties {

    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_ROUTER = "router";

    /** {@code standalone} or {@code router}; no other value is accepted. */
    private String mode;

    private final Router router = new Router();

    @PostConstruct
    public void validate() {
        if (!MODE_STANDALONE.equals(mode) && !MODE_ROUTER.equals(mode)) {
            throw new IllegalStateException("app.datasource.mode must be '" + MODE_STANDALONE
                    + "' or '" + MODE_ROUTER + "' but was: " + mode
                    + " (set the MYSQL_MODE environment variable)");
        }
        if (isRouter()) {
            requireEndpoint(router.getWriteUrl(),
                    "app.datasource.router.write-url", "DB_WRITE_URL", "6446", "read-write");
            requireEndpoint(router.getReadUrl(),
                    "app.datasource.router.read-url", "DB_READ_URL", "6447", "read-only");
        }
    }

    private static void requireEndpoint(String url, String key, String env,
                                        String port, String route) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("MYSQL_MODE=router requires the MySQL Router "
                    + route + " endpoint in " + key + " (env " + env
                    + ", MySQL Router port " + port + "); the app refuses to fall back"
                    + " to the standalone dev MySQL");
        }
    }

    public boolean isRouter() {
        return MODE_ROUTER.equals(mode);
    }

    /**
     * Effective write-pool URL for the active mode: the configured standalone
     * endpoint unless {@code MYSQL_MODE=router}, in which case the Router
     * read-write endpoint wins.
     */
    public String effectiveWriteUrl(String standaloneUrl) {
        return isRouter() ? router.getWriteUrl() : standaloneUrl;
    }

    /** Read-pool counterpart of {@link #effectiveWriteUrl(String)}. */
    public String effectiveReadUrl(String standaloneUrl) {
        return isRouter() ? router.getReadUrl() : standaloneUrl;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Router getRouter() {
        return router;
    }

    /** MySQL Router endpoints; consulted only in {@code router} mode. */
    public static class Router {

        /** {@code DB_WRITE_URL}: Router 6446 (read-write, follows the primary). */
        private String writeUrl;

        /** {@code DB_READ_URL}: Router 6447 (read-only, secondaries). */
        private String readUrl;

        public String getWriteUrl() {
            return writeUrl;
        }

        public void setWriteUrl(String writeUrl) {
            this.writeUrl = writeUrl;
        }

        public String getReadUrl() {
            return readUrl;
        }

        public void setReadUrl(String readUrl) {
            this.readUrl = readUrl;
        }
    }
}
