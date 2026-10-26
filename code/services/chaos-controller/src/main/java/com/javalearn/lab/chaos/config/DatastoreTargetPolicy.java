package com.javalearn.lab.chaos.config;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Fault-target policy for datastore-looking containers (M3-1c).
 *
 * <p>Task 22b hard-rejected every target whose name contains a datastore
 * keyword, which made the Redis Sentinel drill undriveable from the console.
 * That absolute prohibition is now a <strong>closed-by-default allow-list</strong>:
 * {@code chaos.datastore.allowed-target-pattern} (env
 * {@code CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN}), matched against the whole
 * container name. An empty or blank pattern - the default - refuses everything,
 * so {@code jl-redis} (the standalone node), {@code jl-mysql},
 * {@code jl-rabbitmq}, the {@code jl-redis-*-exporter} sidecars and every other
 * datastore-named container behave exactly as before. Only a name matched by the
 * configured pattern is admitted.</p>
 *
 * <p>The rule is enforced twice on purpose: here in {@code ChaosActionService}
 * (the real boundary, HTTP 403 {@code 40301}) and in
 * {@code GuideScenarioLoader} (fast pre-flight, HTTP 400) so a scenario is
 * rejected before any step runs. Both paths call the same code, so the two
 * verdicts can never drift. Mirrored in JavaScript by
 * {@code code/lab/k6/lib/chaos-schema.js}.</p>
 *
 * <p><strong>Asymmetry introduced by M3-1c</strong>: widening
 * {@code chaos.guide.target-pattern} alone still refuses datastores - the
 * keyword test runs first and the allow-list is a second, independent gate. A
 * refusal names the rule that produced it. An automatic TTL revert
 * ({@code start}/{@code SIGCONT}) is <em>never</em> gated by this policy: a
 * fault that was allowed to be applied must always be allowed to be undone,
 * even if the operator removes the pattern while it is active.</p>
 *
 * <p>The pattern is compiled in the constructor, so an invalid regex fails the
 * context at startup instead of surfacing as a runtime 500 - the same treatment
 * {@code chaos.guide.target-pattern} gets.</p>
 */
public final class DatastoreTargetPolicy {

    /** Names that read as a stateful datastore; refused unless the allow-list admits them. */
    private static final Pattern DATASTORE_KEYWORD =
            Pattern.compile("(?i)(mysql|redis|rabbit|mongo|postgres)");

    /** Rule key quoted in every rejection message; also the config property name. */
    public static final String RULE_KEY = "chaos.datastore.allowed-target-pattern";

    private final String configured;
    private final Pattern allowed;

    public DatastoreTargetPolicy(String allowedTargetPattern) {
        this.configured = allowedTargetPattern == null ? "" : allowedTargetPattern.trim();
        if (this.configured.isEmpty()) {
            this.allowed = null;
            return;
        }
        try {
            this.allowed = Pattern.compile(this.configured);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(RULE_KEY + " is not a valid regular expression: "
                    + this.configured, e);
        }
    }

    /** Policy built from {@code chaos.datastore.*}. */
    public static DatastoreTargetPolicy from(ChaosProperties properties) {
        return new DatastoreTargetPolicy(properties.getDatastore().getAllowedTargetPattern());
    }

    /** True when the name looks like a datastore (case-insensitive keyword search). */
    public boolean isDatastoreLike(String target) {
        return target != null && DATASTORE_KEYWORD.matcher(target).find();
    }

    /** True when a fault on this target passes this rule. */
    public boolean permits(String target) {
        if (!isDatastoreLike(target)) {
            return true;
        }
        return allowed != null && allowed.matcher(target).matches();
    }

    /**
     * Rejection reason naming the rule that refused, or {@code null} when the
     * target is permitted. The empty-allow-list wording keeps the historical
     * "is a datastore; datastore actions are forbidden" sentence so existing
     * operator notes and the mirrored JS/k6 tests stay valid.
     */
    public String rejectionReason(String target) {
        if (permits(target)) {
            return null;
        }
        String keywords = DATASTORE_KEYWORD.pattern();
        if (allowed == null) {
            return "target \"" + target + "\" is a datastore; datastore actions are forbidden ("
                    + RULE_KEY + " is empty: no datastore container may be faulted; keyword set "
                    + keywords + ")";
        }
        return "target \"" + target + "\" is a datastore and is refused by " + RULE_KEY + "=\""
                + configured + "\": only a datastore container matching that allow-list may be "
                + "faulted (keyword set " + keywords + ")";
    }
}
