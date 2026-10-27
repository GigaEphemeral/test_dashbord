package com.javalearn.lab.seckill.lab.config;

import com.javalearn.lab.seckill.lab.api.LabCacheConfigItem;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The B0-04 read-back contract: what {@code GET /lab/config} reports must be
 * what the running environment resolved, never a second table of constants.
 *
 * <p>These tests pin the three cases the drill depends on — the declared default
 * in effect, a {@code LAB_CACHE_*} override in effect, and an override from a
 * source that outranks both — plus the discovery rule (everything under
 * {@code app.lab.cache.}, nothing outside it, sorted for diffing two instances).
 * The final test runs the reader against the real {@code application.yml}, which
 * is what makes "配置、应用有效值、键 TTL 三者一致" checkable from one payload.</p>
 */
class CacheEffectiveConfigReaderTest {

    /** The name Boot gives the shipped {@code application.yml} source. */
    private static final String YML_SOURCE = "applicationConfig: [classpath:/application.yml]";
    private static final String SYSTEM_ENVIRONMENT = "systemEnvironment";

    private static final String TTL_KEY = "app.lab.cache.l2.ttl-seconds";
    private static final String TTL_ENV = "LAB_CACHE_L2_TTL_SECONDS";
    private static final String SCAN_KEY = "app.lab.cache.l2.clear-scan-count";
    private static final String SCAN_ENV = "LAB_CACHE_L2_CLEAR_SCAN_COUNT";

    @Test
    void reportsTheDeclaredDefaultWhileNoEnvironmentVariableIsSet() {
        LabCacheConfigItem item = item(read(standalone()), TTL_KEY);

        // The declared default IS the effective value: this is the "operator set
        // nothing yet" state the lab starts in.
        assertThat(item.configKey()).isEqualTo(TTL_KEY);
        assertThat(item.env()).isEqualTo(TTL_ENV);
        assertThat(item.value()).isEqualTo("300");
        assertThat(item.defaultValue()).isEqualTo("300");
        assertThat(item.source()).isEqualTo(YML_SOURCE);
    }

    @Test
    void reportsSystemEnvironmentWhenTheVariableIsSet() {
        MockEnvironment environment = standalone();
        systemEnvironmentSource(environment)
                .withProperty(TTL_ENV, "45");

        LabCacheConfigItem item = item(read(environment), TTL_KEY);

        assertThat(item.value()).isEqualTo("45");
        // The declared default stays visible next to the override, so the payload
        // answers "did my change take effect, and what would it have been?".
        assertThat(item.defaultValue()).isEqualTo("300");
        assertThat(item.env()).isEqualTo(TTL_ENV);
        assertThat(item.source()).isEqualTo(SYSTEM_ENVIRONMENT);
    }

    @Test
    void reportsTheWinningSourceWhenAHigherPrecedenceSourceDeclaresTheKey() {
        MockEnvironment environment = standalone();
        systemEnvironmentSource(environment).withProperty(TTL_ENV, "45");
        environment.getPropertySources().addFirst(new MockPropertySource("commandLineArgs")
                .withProperty(TTL_KEY, "90"));

        LabCacheConfigItem item = item(read(environment), TTL_KEY);

        // Boot's own precedence decides, and the reader reports the real winner
        // instead of relabelling it as an environment override.
        assertThat(item.value()).isEqualTo("90");
        assertThat(item.source()).isEqualTo("commandLineArgs");
        assertThat(item.defaultValue()).isEqualTo("300");
        assertThat(item.env()).isEqualTo(TTL_ENV);
    }

    @Test
    void reportsOnlyTheCacheFamilyAndInKeyOrder() {
        List<LabCacheConfigItem> items = read(standalone());

        // Sorted by config key so two instances of the lab can be diffed directly;
        // app.lab.token and server.port are declared in the same source and must
        // stay out of the payload.
        assertThat(items).extracting(LabCacheConfigItem::configKey).containsExactly(
                SCAN_KEY,
                "app.lab.cache.l2.key-prefix",
                TTL_KEY,
                "app.lab.cache.state.scan-count");
    }

    @Test
    void aKeyWithoutAPlaceholderIsReportedWithoutEnvOrDefault() {
        MockEnvironment environment = isolated();
        environment.withProperty(TTL_KEY, "42");

        LabCacheConfigItem item = item(read(environment), TTL_KEY);

        assertThat(item.value()).isEqualTo("42");
        assertThat(item.env()).isNull();
        assertThat(item.defaultValue()).isNull();
        assertThat(item.source()).isEqualTo("mockProperties");
    }

    @Test
    void aNestedPlaceholderDefaultStillResolvesAndTheOuterVariableIsReported() {
        MockEnvironment environment = isolated();
        environment.withProperty(SCAN_KEY, "${" + SCAN_ENV + ":${LAB_SCAN_FALLBACK:250}}");

        LabCacheConfigItem item = item(read(environment), SCAN_KEY);

        // application.yml declares a plain ${ENV:default} for every cache key, but
        // a nested default must not be mistaken for an environment variable name
        // nor leak an unresolved placeholder into the payload.
        assertThat(item.env()).isEqualTo(SCAN_ENV);
        assertThat(item.defaultValue()).isEqualTo("${LAB_SCAN_FALLBACK:250}");
        assertThat(item.value()).isEqualTo("250");
    }

    @Test
    void everyRealCacheKeyCarriesAnEnvOverrideABindableValueAndADefault() throws IOException {
        MockEnvironment environment = isolated();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        List<LabCacheConfigItem> items = read(environment);

        // The wiring B0-04 delivered: every app.lab.cache.* key in the shipped
        // yml names the LAB_CACHE_* variable that overrides it and declares a
        // default, so a value can never be reachable only through a rebuild.
        assertThat(items).hasSizeGreaterThanOrEqualTo(50);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.env()).as("env override of %s", item.configKey()).isNotBlank();
            assertThat(item.defaultValue()).as("default of %s", item.configKey()).isNotBlank();
            assertThat(item.value()).as("effective value of %s", item.configKey()).isNotBlank();
            assertThat(item.source()).as("source of %s", item.configKey()).isNotBlank();
        });

        LabCacheConfigItem ttl = item(items, TTL_KEY);
        assertThat(ttl.env()).isEqualTo(TTL_ENV);
        assertThat(ttl.value()).isEqualTo("300");
        assertThat(ttl.defaultValue()).isEqualTo("300");
        assertThat(item(items, "app.lab.cache.l2.key-prefix").value()).isEqualTo("cache:");
    }

    /**
     * The yml stand-in: the L2 family plus one neighbouring key, and two values
     * outside {@code app.lab.cache.} that must never be reported.
     */
    private static MockPropertySource yamlSource() {
        return new MockPropertySource(YML_SOURCE)
                .withProperty(TTL_KEY, "${" + TTL_ENV + ":300}")
                .withProperty("app.lab.cache.l2.key-prefix", "${LAB_CACHE_L2_KEY_PREFIX:cache:}")
                .withProperty(SCAN_KEY, "${" + SCAN_ENV + ":500}")
                .withProperty("app.lab.cache.state.scan-count", "${LAB_CACHE_STATE_SCAN_COUNT:500}")
                .withProperty("app.lab.token", "${LAB_TOKEN:change-me}")
                .withProperty("server.port", "8080");
    }

    /** yml at the bottom and an empty, controlled {@code systemEnvironment} on top. */
    private static MockEnvironment standalone() {
        MockEnvironment environment = isolated();
        environment.getPropertySources().addFirst(new MockPropertySource(SYSTEM_ENVIRONMENT));
        environment.getPropertySources().addLast(yamlSource());
        return environment;
    }

    /**
     * No host leakage: the real {@code systemEnvironment} / {@code systemProperties}
     * sources are dropped, so a {@code LAB_CACHE_*} variable exported on a
     * developer machine or CI agent cannot flip these assertions.
     */
    private static MockEnvironment isolated() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().remove(SYSTEM_ENVIRONMENT);
        environment.getPropertySources().remove("systemProperties");
        return environment;
    }

    private static MockPropertySource systemEnvironmentSource(MockEnvironment environment) {
        return (MockPropertySource) environment.getPropertySources().get(SYSTEM_ENVIRONMENT);
    }

    private static List<LabCacheConfigItem> read(MockEnvironment environment) {
        return new CacheEffectiveConfigReader(environment).read();
    }

    private static LabCacheConfigItem item(List<LabCacheConfigItem> items, String configKey) {
        return items.stream()
                .filter(candidate -> candidate.configKey().equals(configKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        configKey + " missing from " + items.stream()
                                .map(LabCacheConfigItem::configKey).toList()));
    }
}
