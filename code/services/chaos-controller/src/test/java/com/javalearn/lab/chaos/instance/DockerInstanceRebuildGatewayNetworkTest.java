package com.javalearn.lab.chaos.instance;

import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ha overlay puts the app tier on jl-sentinel-net as a second network;
 * a controlled rebuild must re-attach it, otherwise a sentinel-mode app comes
 * back unable to reach the master.
 */
class DockerInstanceRebuildGatewayNetworkTest {

    private static InstanceSnapshot snapshot() {
        return new InstanceSnapshot(
                "app-1", "jl-app-1", "old-id", "img", "img-id",
                Map.of(), Map.of("lab", "java-learn"),
                List.of(), List.of(), "/", null, "host",
                List.of(),
                Map.of("jl-net", List.of("app-1"), "jl-sentinel-net", List.of()),
                null,
                HostConfig.newHostConfig().withNetworkMode("jl-net"));
    }

    @Test
    void keepsTheSecondaryNetworkAndItsAliases() {
        Map<String, List<String>> extra =
                DockerInstanceRebuildGateway.extraNetworks(snapshot(), "jl-net");

        assertThat(extra).containsOnlyKeys("jl-sentinel-net");
        assertThat(extra.get("jl-sentinel-net")).isEmpty();
    }

    @Test
    void aMissingPrimaryAttachesEveryRecordedNetwork() {
        Map<String, List<String>> extra =
                DockerInstanceRebuildGateway.extraNetworks(snapshot(), null);

        assertThat(extra).containsOnlyKeys("jl-net", "jl-sentinel-net");
        assertThat(extra.get("jl-net")).containsExactly("app-1");
    }
}
