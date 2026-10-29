package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4-01 validator matrix: JDK/GC gating, budget arithmetic, extra-arg
 * injection, GC-log path escape and argv generation. No Docker, no ledger.
 */
class InstanceConfigValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstanceConfigProperties properties;
    private InstanceConfigValidator validator;

    @BeforeEach
    void setUp() {
        properties = new InstanceConfigProperties();
        properties.validate();
        validator = new InstanceConfigValidator(properties);
    }

    @Test
    void acceptsABaselineG1ConfigAndGeneratesArgvElements() throws Exception {
        InstanceConfigValidator.ValidationResult result = validator.validate("app-1", config("""
                {
                  "jdkVersion": 21,
                  "imageTag": "jdk21",
                  "xmsMb": 128, "xmxMb": 256,
                  "metaspaceMb": 128, "directMemoryMb": 64,
                  "extraJvmArgs": ["-Dfile.encoding=UTF-8", "-XX:+UseStringDeduplication"],
                  "gcCollector": "g1", "maxGcPauseMillis": 200,
                  "g1NewSizePercent": 5, "g1MaxNewSizePercent": 60,
                  "g1HeapRegionSize": "4M", "initiatingHeapOccupancyPercent": 45,
                  "newRatio": 2, "survivorRatio": 8,
                  "corePoolSize": 16, "maxPoolSize": 64, "queueCapacity": 500,
                  "keepAliveSeconds": 60, "rejectionPolicy": "abortpolicy",
                  "maximumSize": 10000, "expireAfterWriteSeconds": 0,
                  "gcLogEnabled": true, "gcLogFileSizeMb": 20, "gcLogFileCount": 5,
                  "heapDumpOnOutOfMemory": true
                }
                """));

        assertThat(result.config().gcCollector()).isEqualTo("G1");
        assertThat(result.config().g1HeapRegionSize()).isEqualTo("4m");
        assertThat(result.config().rejectionPolicy()).isEqualTo("AbortPolicy");
        assertThat(result.config().gcLogPath()).isEqualTo("/app/logs/gc-app-1.log");
        assertThat(result.config().heapDumpPath()).isEqualTo("/app/logs/heap-app-1.hprof");
        assertThat(result.limitedObservability()).isFalse();
        assertThat(result.requiredMemoryMb()).isEqualTo(256 + 128 + 64 + 64 + 128);
        assertThat(result.jvmArgs()).containsExactly(
                "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m",
                "-XX:MaxDirectMemorySize=64m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200",
                "-XX:G1NewSizePercent=5", "-XX:G1MaxNewSizePercent=60",
                "-XX:G1HeapRegionSize=4m", "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:NewRatio=2", "-XX:SurvivorRatio=8",
                "-Xlog:gc*:file=/app/logs/gc-app-1.log:time,level,tags:filecount=5,filesize=20m",
                "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/logs/heap-app-1.hprof",
                "-Dfile.encoding=UTF-8", "-XX:+UseStringDeduplication");
    }

    @Test
    void requiresJdkVersionAndAcceptsOnly8Or21() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1", config("{}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("jdkVersion is required");
        assertThatThrownBy(() -> validator.validate("app-1", config("{\"jdkVersion\":11}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must be 8 or 21");
    }

    @Test
    void rejectsUnknownInstance() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-9", config("{\"jdkVersion\":21}")))
                .isInstanceOf(InstanceNotFoundException.class)
                .hasMessageContaining("app-9");
    }

    @Test
    void rejectsXmxBelowXmsAndUnknownImageTag() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"xmsMb\":512,\"xmxMb\":256}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must be >= xmsMb");
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"imageTag\":\"latest\"}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("not allow-listed");
    }

    @Test
    void gatesG1AndParallelOnlyFieldsByCollector() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"gcCollector\":\"Parallel\",\"maxGcPauseMillis\":200}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("only valid when gcCollector=G1");
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"gcCollector\":\"G1\",\"parallelGcThreads\":4}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("only valid when gcCollector=Parallel");
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"gcCollector\":\"G1\",\"g1NewSizePercent\":60,"
                        + "\"g1MaxNewSizePercent\":10}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must be <= g1MaxNewSizePercent");
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"g1HeapRegionSize\":\"3m\"}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("g1HeapRegionSize");
        assertThatThrownBy(() -> validator.validate("app-1",
                config("{\"jdkVersion\":21,\"gcCollector\":\"G1\",\"initiatingHeapOccupancyPercent\":101}")))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("initiatingHeapOccupancyPercent");
    }

    @Test
    void enforcesTheMemoryBudgetAndShowsTheArithmetic() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"xmxMb":512,"metaspaceMb":128,"directMemoryMb":64,
                 "maxPoolSize":64}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("memory budget exceeded")
                .hasMessageContaining("xmx=512")
                .hasMessageContaining("threads=64x1=64")
                .hasMessageContaining("= 896 MB > memLimit 768 MB");
    }

    @Test
    void rejectsInjectionAndPathTokensInExtraArgs() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["-javaagent:/tmp/a.jar"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["-Dfoo=/etc/passwd"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("shell character or a path");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["-Dx=hello;rm"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("shell character or a path");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["-Dx=../secret"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("shell character or a path");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["--add-opens=java.base/java.lang=ALL-UNNAMED"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must start with -X, -XX: or -D");
    }

    @Test
    void capsExtraArgCountAndTotalLength() throws Exception {
        InstanceConfigProperties tight = new InstanceConfigProperties();
        tight.setMaxExtraArgs(1);
        tight.validate();
        InstanceConfigValidator tightValidator = new InstanceConfigValidator(tight);
        assertThatThrownBy(() -> tightValidator.validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["-Da=1","-Db=2"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("cap is 1");

        InstanceConfigProperties shortArgs = new InstanceConfigProperties();
        shortArgs.setMaxExtraArgsLength(8);
        shortArgs.validate();
        assertThatThrownBy(() -> new InstanceConfigValidator(shortArgs).validate("app-1", config("""
                {"jdkVersion":21,"extraJvmArgs":["-Dabcdef=1"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("total length");
    }

    @Test
    void jdk8RejectsJdk21OnlyFlagsAndMarksLimitedObservability() throws Exception {
        InstanceConfigValidator.ValidationResult result = validator.validate("app-1", config("""
                {"jdkVersion":8,"gcCollector":"G1","gcLogEnabled":true}
                """));
        assertThat(result.limitedObservability()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("LIMITED-OBSERVABILITY"));
        assertThat(result.jvmArgs()).contains("-Xloggc:/app/logs/gc-app-1.log");

        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":8,"extraJvmArgs":["-Xlog:gc*"]}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("JDK 21-only");
    }

    @Test
    void rejectsGcLogAndHeapDumpPathsOutsideTheAllowListedRoot() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"gcLogEnabled":true,"gcLogPath":"/etc/passwd"}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("escapes the allow-listed root");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"gcLogEnabled":true,"gcLogPath":"../escape.log"}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must not contain '..'");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"heapDumpOnOutOfMemory":true,"heapDumpPath":"/tmp/heap.hprof"}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("escapes the allow-listed root");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"gcLogEnabled":true,"gcLogPath":"/app/logs/a;rm.log"}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must match");
    }

    @Test
    void zeroIsAcceptedOnlyForCaffeineExpiryAndRefresh() throws Exception {
        InstanceConfigValidator.ValidationResult result = validator.validate("app-1", config("""
                {"jdkVersion":21,"expireAfterWriteSeconds":0,"expireAfterAccessSeconds":0,
                 "refreshAfterWriteSeconds":0}
                """));
        assertThat(result.config().expireAfterWriteSeconds()).isZero();
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"maximumSize":0}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("maximumSize");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"queueCapacity":0}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("queueCapacity");
    }

    @Test
    void rejectsMaxPoolBelowCoreAndUnknownRejectionPolicy() throws Exception {
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"corePoolSize":64,"maxPoolSize":16}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("must be >= corePoolSize");
        assertThatThrownBy(() -> validator.validate("app-1", config("""
                {"jdkVersion":21,"rejectionPolicy":"BlockPolicy"}
                """)))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("rejectionPolicy");
    }

    @Test
    void detectsStartupFieldChangesButIgnoresPoolCaffeineAndTomcat() throws Exception {
        InstanceConfig stored = config("""
                {"jdkVersion":21,"xmxMb":256,"corePoolSize":16,"maximumSize":1000,
                 "tomcatMaxThreads":200}
                """);
        InstanceConfig poolOnly = config("""
                {"jdkVersion":21,"xmxMb":256,"corePoolSize":32,"maximumSize":2000,
                 "tomcatMaxThreads":100}
                """);
        InstanceConfig startup = config("""
                {"jdkVersion":21,"xmxMb":512,"corePoolSize":16,"maximumSize":1000,
                 "tomcatMaxThreads":200}
                """);

        assertThat(InstanceConfigValidator.startupFieldsChanged(poolOnly, stored)).isFalse();
        assertThat(InstanceConfigValidator.startupFieldsChanged(startup, stored)).isTrue();
        assertThat(InstanceConfigValidator.startupFieldsChanged(stored, null)).isTrue();
    }

    private static InstanceConfig config(String json) throws Exception {
        return MAPPER.readValue(json, InstanceConfig.class);
    }
}
