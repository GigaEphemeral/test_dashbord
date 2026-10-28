package com.javalearn.lab.chaos.bench.exec;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the live A01 bug: docker-java's
 * {@code AccessMode.fromBoolean(true)} means read-write, so passing the plan's
 * {@code readOnly} flag through mounted scripts rw and the reports volume ro
 * (k6 then could not write summary.json).
 */
class DockerK6ContainerGatewayBindTest {

    @Test
    void aReadOnlyPlanBindBecomesRo() {
        Bind bind = DockerK6ContainerGateway.toBind(
                new BenchLaunchPlan.Bind("/host/k6", "/scripts", true));

        assertThat(bind.getPath()).isEqualTo("/host/k6");
        assertThat(bind.getVolume().getPath()).isEqualTo("/scripts");
        assertThat(bind.getAccessMode()).isEqualTo(AccessMode.ro);
    }

    @Test
    void aReadWritePlanBindBecomesRw() {
        Bind bind = DockerK6ContainerGateway.toBind(
                new BenchLaunchPlan.Bind("jl-k6-reports", "/reports", false));

        assertThat(bind.getPath()).isEqualTo("jl-k6-reports");
        assertThat(bind.getVolume().getPath()).isEqualTo("/reports");
        assertThat(bind.getAccessMode()).isEqualTo(AccessMode.rw);
    }
}
