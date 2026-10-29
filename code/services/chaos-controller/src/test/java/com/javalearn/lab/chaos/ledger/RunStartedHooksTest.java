package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunStartedHooksTest {

    @Test
    void everyHookRunsAndAFailingOneDoesNotStopTheRest() {
        List<String> seen = new ArrayList<>();

        RunStartedHook composite = RunStartedHooks.composite(List.of(
                runId -> {
                    throw new IllegalStateException("boom");
                },
                seen::add));

        assertThatCode(() -> composite.onStarted("run-1")).doesNotThrowAnyException();
        assertThat(seen).containsExactly("run-1");
    }

    @Test
    void anEmptyListIsTheNoOpHook() {
        RunStartedHook composite = RunStartedHooks.composite(List.of());

        assertThatCode(() -> composite.onStarted("run-1")).doesNotThrowAnyException();
    }
}
