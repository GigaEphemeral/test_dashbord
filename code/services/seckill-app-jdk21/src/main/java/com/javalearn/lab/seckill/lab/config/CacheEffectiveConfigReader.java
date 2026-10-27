package com.javalearn.lab.seckill.lab.config;

import com.javalearn.lab.seckill.lab.api.LabCacheConfigItem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads back the <em>effective</em> configuration of the cache family
 * ({@code app.lab.cache.*}) for {@code GET /lab/config} — the B0-04 requirement
 * that "配置、应用有效值、键 TTL" be comparable in one place.
 *
 * <p>Nothing is declared here: every item comes out of the running
 * {@link ConfigurableEnvironment}, so the report can never drift from what the
 * application actually bound (and a new {@code app.lab.cache.*} key appears in
 * the payload without a code change). The key set is discovered from the
 * enumerable property sources, the effective value from
 * {@code environment.getProperty(key)}, and the env name / declared default from
 * the unresolved {@code ${ENV:default}} placeholder text still carried by the
 * {@code application.yml} source.</p>
 *
 * <p>{@code source} says where the effective value came from:
 * {@code systemEnvironment} means the {@code LAB_CACHE_*} override is in effect
 * (Boot resolved the {@code ${ENV:default}} placeholder against the environment),
 * otherwise it is the name of the highest-precedence property source declaring
 * the key — {@code applicationConfig: [classpath:/application.yml]} therefore
 * means "the declared default is in effect". A value coming from anywhere else
 * (a profile file, a test property source, an {@code --arg}) is reported under
 * its own name, never silently relabelled as a default.</p>
 */
public final class CacheEffectiveConfigReader {

    /** The cache configuration family reported by {@code GET /lab/config}. */
    static final String CONFIG_PREFIX = "app.lab.cache.";

    private static final String PLACEHOLDER_START = "${";

    private static final String SYSTEM_ENVIRONMENT = "systemEnvironment";

    private final ConfigurableEnvironment environment;

    public CacheEffectiveConfigReader(ConfigurableEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Every {@code app.lab.cache.*} leaf with its effective value, the env
     * variable that can override it, the declared default and the winning
     * property source; sorted by config key so two instances can be diffed.
     */
    public List<LabCacheConfigItem> read() {
        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith(CONFIG_PREFIX)) {
                    keys.add(name);
                }
            }
        }
        List<LabCacheConfigItem> items = new ArrayList<>(keys.size());
        for (String key : keys) {
            items.add(readOne(key));
        }
        return List.copyOf(items);
    }

    private LabCacheConfigItem readOne(String key) {
        String value = environment.getProperty(key);
        String winner = null;
        String winnerCarryingPlaceholder = null;
        String declared = null;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!source.containsProperty(key)) {
                continue;
            }
            if (winner == null) {
                winner = source.getName();
            }
            if (declared == null && source.getProperty(key) instanceof String raw
                    && raw.startsWith(PLACEHOLDER_START)) {
                declared = raw;
                winnerCarryingPlaceholder = source.getName();
            }
        }
        return new LabCacheConfigItem(key, envNameOf(declared), value,
                defaultValueOf(declared), sourceOf(winner, winnerCarryingPlaceholder, declared));
    }

    /**
     * Where the effective value really came from: the environment variable when
     * it is set and no source outranks the placeholder declaration, otherwise
     * the winning property source.
     */
    private String sourceOf(String winner, String placeholderSource, String declared) {
        String envName = envNameOf(declared);
        if (envName != null && Objects.equals(winner, placeholderSource)
                && environment.containsProperty(envName)) {
            return SYSTEM_ENVIRONMENT;
        }
        return winner;
    }

    /** {@code ${LAB_CACHE_L2_TTL_SECONDS:300}} -&gt; {@code LAB_CACHE_L2_TTL_SECONDS}. */
    private static String envNameOf(String declared) {
        String body = bodyOf(declared);
        if (body == null) {
            return null;
        }
        int separator = body.indexOf(':');
        String name = separator < 0 ? body : body.substring(0, separator);
        // A nested placeholder as the "name" (${A:${B:c}}) is a default, not an env var.
        return (name.isEmpty() || name.startsWith(PLACEHOLDER_START)) ? null : name;
    }

    /** {@code ${LAB_CACHE_L2_TTL_SECONDS:300}} -&gt; {@code 300} (null when no default). */
    private static String defaultValueOf(String declared) {
        String body = bodyOf(declared);
        if (body == null) {
            return null;
        }
        int separator = body.indexOf(':');
        if (separator < 0) {
            return null;
        }
        return body.substring(separator + 1);
    }

    private static String bodyOf(String declared) {
        if (declared == null || !declared.startsWith(PLACEHOLDER_START)
                || !declared.endsWith("}")) {
            return null;
        }
        return declared.substring(PLACEHOLDER_START.length(), declared.length() - 1);
    }
}
