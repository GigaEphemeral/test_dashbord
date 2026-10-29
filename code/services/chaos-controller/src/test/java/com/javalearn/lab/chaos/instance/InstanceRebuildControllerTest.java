package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.error.InstanceConfigConflictException;
import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.error.InstanceRebuildConflictException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.javalearn.lab.chaos.instance.InstanceViews.ApplyCheckView;
import static com.javalearn.lab.chaos.instance.InstanceViews.InstanceApplyView;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B4-02 HTTP routing/status mapping (service stubbed). */
class InstanceRebuildControllerTest {

    private InstanceRebuildService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(InstanceRebuildService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InstanceRebuildController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void applyReturnsTheEvidenceWithStablePeriod() throws Exception {
        when(service.apply("app-1")).thenReturn(new InstanceApplyView("app-1", "APPLY", "APPLIED",
                false, "NOT_NEEDED", "jl-app-1", "new-id", "java-learn-lab/seckill-app:jdk21",
                "sha256:new", "java-learn-lab/seckill-app:jdk21", "sha256:old",
                "-Xms256m -Xmx384m", List.of(new ApplyCheckView("app-probe", "PASS", 12L, "ok")),
                4321L, "2026-10-30T10:01:00Z", null, null, 2));

        mvc.perform(post("/lab/instances/app-1/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.action").value("APPLY"))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.imageId").value("sha256:new"))
                .andExpect(jsonPath("$.data.stableUntil").value("2026-10-30T10:01:00Z"))
                .andExpect(jsonPath("$.data.checks[0].name").value("app-probe"));
    }

    @Test
    void restartReturnsTheSameEvidenceWithTheRestartAction() throws Exception {
        when(service.restart("app-1")).thenReturn(new InstanceApplyView("app-1", "RESTART",
                "APPLIED", false, "NOT_NEEDED", "jl-app-1", "new-id",
                "java-learn-lab/seckill-app:jdk21", "sha256:new",
                "java-learn-lab/seckill-app:jdk21", "sha256:new", "-Xms256m -Xmx384m",
                List.of(new ApplyCheckView("docker-health", "PASS", 10L, "healthy")),
                1234L, "2026-10-30T10:01:00Z", null, null, 2));

        mvc.perform(post("/lab/instances/app-1/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("RESTART"))
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
    }

    @Test
    void rollbackReturnsTheRolledBackEvidence() throws Exception {
        when(service.rollback("app-1")).thenReturn(new InstanceApplyView("app-1", "ROLLBACK",
                "APPLIED", false, "NOT_NEEDED", "jl-app-1", "new-id",
                "java-learn-lab/seckill-app:jdk21", "sha256:new",
                "java-learn-lab/seckill-app:jdk21", "sha256:old", "-Xmx256m", List.of(), 1000L,
                "2026-10-30T10:01:00Z", null, null, 3));

        mvc.perform(post("/lab/instances/app-1/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("ROLLBACK"))
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
    }

    @Test
    void failedRebuildIs200EvidenceNotAnOpaque500() throws Exception {
        when(service.apply("app-1")).thenReturn(new InstanceApplyView("app-1", "APPLY", "FAILED",
                true, "RESTORED", "jl-app-1", "new-id", "java-learn-lab/seckill-app:jdk21",
                "sha256:old", "java-learn-lab/seckill-app:jdk21", "sha256:old", "-Xmx384m",
                List.of(new ApplyCheckView("app-probe", "FAIL", 5L, "HTTP 500")), 9000L,
                "2026-10-30T10:01:00Z", "app-probe", "app probe failed: HTTP 500", 2));

        mvc.perform(post("/lab/instances/app-1/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.rolledBack").value(true))
                .andExpect(jsonPath("$.data.rollback").value("RESTORED"))
                .andExpect(jsonPath("$.data.failedCheck").value("app-probe"))
                .andExpect(jsonPath("$.data.stableUntil").value("2026-10-30T10:01:00Z"));
    }

    @Test
    void noConfigIs409With40907() throws Exception {
        when(service.apply("app-1")).thenThrow(new InstanceRebuildConflictException(
                "no stored configuration for app-1"));

        mvc.perform(post("/lab/instances/app-1/apply"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40907"));
    }

    @Test
    void runningRunIs409With40906() throws Exception {
        when(service.apply("app-1")).thenThrow(new InstanceConfigConflictException(
                "run run-20261030-001 is RUNNING"));

        mvc.perform(post("/lab/instances/app-1/apply"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40906"));
    }

    @Test
    void jdk8Is400With40001() throws Exception {
        when(service.apply("app-1")).thenThrow(new InvalidInstanceConfigException(
                "instance app-1 cannot be rebuilt with the jdk8 image"));

        mvc.perform(post("/lab/instances/app-1/apply"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void degradedLedgerIs503With50302() throws Exception {
        when(service.apply("app-1")).thenThrow(new LedgerUnavailableException(
                "ledger state is DEGRADED"));

        mvc.perform(post("/lab/instances/app-1/apply"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"));
    }

    @Test
    void unknownInstanceIs404() throws Exception {
        when(service.apply("app-9")).thenThrow(new InstanceNotFoundException("unknown instance app-9"));

        mvc.perform(post("/lab/instances/app-9/apply"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }
}
