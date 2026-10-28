package com.javalearn.lab.chaos.bench.exec;

import com.javalearn.lab.chaos.error.BenchUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host k6 directory resolution (Stage2 B2-02b-1): no Spring, no Docker. The
 * mount source is a recording lambda so the resolution rules are pinned without
 * a daemon.
 */
class BenchScriptsHostDirResolverTest {

    private final BenchExecProperties properties = new BenchExecProperties();
    private final AtomicInteger inspectCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        properties.setScriptsHostDir("");
        properties.setControllerContainer("jl-chaos-controller");
        properties.setControllerK6Mount("/app/k6");
        inspectCalls.set(0);
    }

    @Test
    void resolvesTheSingleMatchingMountSource() {
        BenchScriptsHostDirResolver resolver = resolver(List.of(
                new BenchScriptsHostDirResolver.Mount("/var/run/docker.sock", "/var/run/docker.sock"),
                new BenchScriptsHostDirResolver.Mount("/home/lab/JAVA-LEARN/code/lab/k6", "/app/k6"),
                new BenchScriptsHostDirResolver.Mount("jl-ledger-stash-data", "/app/ledger-stash")));

        assertEquals("/home/lab/JAVA-LEARN/code/lab/k6", resolver.resolve());
        assertEquals(1, inspectCalls.get());
    }

    @Test
    void configuredOverrideWinsAndSkipsTheInspect() {
        properties.setScriptsHostDir(" D:/000CODE/JAVA-LEARN/code/lab/k6 ");
        BenchScriptsHostDirResolver resolver = new BenchScriptsHostDirResolver(properties, name -> {
            inspectCalls.incrementAndGet();
            throw new AssertionError("the mount source must not be queried when an override exists");
        });

        assertEquals("D:/000CODE/JAVA-LEARN/code/lab/k6", resolver.resolve());
        assertEquals(0, inspectCalls.get());
    }

    @Test
    void missingMountIsRefusedWithAnActionableMessage() {
        BenchScriptsHostDirResolver resolver = resolver(List.of(
                new BenchScriptsHostDirResolver.Mount("/var/run/docker.sock", "/var/run/docker.sock")));

        BenchUnavailableException ex = assertThrows(BenchUnavailableException.class,
                resolver::resolve);
        assertTrue(ex.getMessage().contains("no mount with destination '/app/k6'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("LAB_BENCH_EXEC_SCRIPTS_HOST_DIR"), ex.getMessage());
    }

    @Test
    void multipleMatchingMountsAreRefused() {
        BenchScriptsHostDirResolver resolver = resolver(List.of(
                new BenchScriptsHostDirResolver.Mount("/host/a", "/app/k6"),
                new BenchScriptsHostDirResolver.Mount("/host/b", "/app/k6/")));

        BenchUnavailableException ex = assertThrows(BenchUnavailableException.class,
                resolver::resolve);
        assertTrue(ex.getMessage().contains("2 mounts with destination '/app/k6'"), ex.getMessage());
    }

    @Test
    void blankSourceIsRefusedInsteadOfBindingAnEmptyPath() {
        BenchScriptsHostDirResolver resolver = resolver(List.of(
                new BenchScriptsHostDirResolver.Mount(" ", "/app/k6")));

        BenchUnavailableException ex = assertThrows(BenchUnavailableException.class,
                resolver::resolve);
        assertTrue(ex.getMessage().contains("blank source"), ex.getMessage());
    }

    @Test
    void missingControllerContainerPropagatesTheUnavailableFailure() {
        BenchScriptsHostDirResolver resolver = new BenchScriptsHostDirResolver(properties, name -> {
            throw new BenchUnavailableException("controller container '" + name + "' not found");
        });

        BenchUnavailableException ex = assertThrows(BenchUnavailableException.class,
                resolver::resolve);
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    void blankControllerConfigurationIsRefusedBeforeAnyInspect() {
        properties.setControllerK6Mount(" ");
        BenchScriptsHostDirResolver resolver = resolver(List.of());

        BenchUnavailableException ex = assertThrows(BenchUnavailableException.class,
                resolver::resolve);
        assertTrue(ex.getMessage().contains("controller-k6-mount"), ex.getMessage());
        assertEquals(0, inspectCalls.get());
    }

    private BenchScriptsHostDirResolver resolver(List<BenchScriptsHostDirResolver.Mount> mounts) {
        return new BenchScriptsHostDirResolver(properties, name -> {
            inspectCalls.incrementAndGet();
            assertEquals("jl-chaos-controller", name);
            return mounts;
        });
    }
}
