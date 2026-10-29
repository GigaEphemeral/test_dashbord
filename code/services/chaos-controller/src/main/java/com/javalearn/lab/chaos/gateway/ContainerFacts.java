package com.javalearn.lab.chaos.gateway;

import java.util.Map;

/**
 * Read-only container facts used by the B4-01 effective-config readback.
 * Only whitelisted env keys are surfaced by the service; the map itself is
 * never returned to the HTTP caller.
 *
 * @param name    canonical container name (without Docker's leading slash)
 * @param image   image reference recorded on the container (tag or digest)
 * @param imageId immutable image id
 * @param env     container environment (KEY=VALUE parsed)
 */
public record ContainerFacts(String name, String image, String imageId, Map<String, String> env) {
}
