package com.javalearn.lab.chaos.ledger;

/**
 * Resolves the ledger JDBC endpoint from {@code lab.ledger.mysql-mode}, using
 * the same topology switch as the app tier and {@code reset-lab.sh}
 * ({@code MYSQL_MODE}):
 *
 * <ul>
 *   <li>{@code standalone}: {@code jl-mysql} via {@code mysql:3306};</li>
 *   <li>{@code router}: the InnoDB Cluster through MySQL Router
 *       {@code mysql-router:6446} (read-write, follows the current PRIMARY).</li>
 * </ul>
 *
 * <p>Validation happens in the constructor, i.e. at context startup. Every
 * message names the property path and the env variable; there is no silent
 * fallback to the standalone endpoint in router mode (same philosophy as
 * {@code MYSQL_MODE} in the app). The server-level URL (database part stripped)
 * is what CREATE DATABASE runs on; the ledger URL is what Flyway and every
 * query use.</p>
 */
public class LedgerTargetResolver {

    private static final String JDBC_PREFIX = "jdbc:mysql://";

    private final LedgerProperties properties;
    private final boolean router;
    private final String propertyName;
    private final String envName;
    private final String ledgerUrl;
    private final String serverUrl;

    public LedgerTargetResolver(LedgerProperties properties) {
        this.properties = properties;
        properties.validate();
        this.router = properties.isRouter();
        this.propertyName = router ? "lab.ledger.router-url" : "lab.ledger.standalone-url";
        this.envName = router ? "LAB_LEDGER_ROUTER_URL" : "LAB_LEDGER_STANDALONE_URL";
        this.ledgerUrl = properties.activeUrl().trim();
        String database = parseDatabase(ledgerUrl, propertyName, envName);
        if (!properties.getSchema().equals(database)) {
            throw new IllegalStateException(propertyName + " must point at the ledger database '"
                    + properties.getSchema() + "' but was '" + database + "'; env " + envName);
        }
        this.serverUrl = stripDatabase(ledgerUrl);
    }

    public boolean isRouter() {
        return router;
    }

    public String mode() {
        return properties.getMysqlMode();
    }

    public String ledgerUrl() {
        return ledgerUrl;
    }

    /** Same endpoint with the database path removed, for CREATE DATABASE. */
    public String serverUrl() {
        return serverUrl;
    }

    private static String parseDatabase(String url, String property, String env) {
        if (!url.startsWith(JDBC_PREFIX)) {
            throw new IllegalStateException(property + " must start with " + JDBC_PREFIX
                    + " (was '" + url + "'); env " + env);
        }
        int slash = url.indexOf('/', JDBC_PREFIX.length());
        int query = url.indexOf('?', JDBC_PREFIX.length());
        if (slash < 0 || (query >= 0 && query < slash)) {
            throw new IllegalStateException(property + " must include the database path /"
                    + "<schema> (was '" + url + "'); env " + env);
        }
        String authority = url.substring(JDBC_PREFIX.length(), slash);
        if (authority.isBlank() || authority.contains("@")) {
            throw new IllegalStateException(property + " must name host:port without credentials (was '"
                    + authority + "'); env " + env);
        }
        int end = query >= 0 ? query : url.length();
        String database = url.substring(slash + 1, end);
        if (database.isBlank() || database.contains("/")) {
            throw new IllegalStateException(property + " must include exactly one database name after '/' (was '"
                    + database + "'); env " + env);
        }
        return database;
    }

    private static String stripDatabase(String url) {
        int slash = url.indexOf('/', JDBC_PREFIX.length());
        int query = url.indexOf('?', JDBC_PREFIX.length());
        String suffix = query >= 0 ? url.substring(query) : "";
        return url.substring(0, slash) + "/" + suffix;
    }
}
