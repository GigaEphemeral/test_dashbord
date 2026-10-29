package com.javalearn.lab.chaos.instance;

import java.util.Map;

/**
 * Stage2 B4-02 controlled container rebuild boundary.
 *
 * <p>Every implementation must enforce the {@code lab=java-learn} label on the
 * target container before any mutating call (same rule as
 * {@link com.javalearn.lab.chaos.gateway.ContainerGateway}) and must never pull
 * an image, mount an arbitrary host path or run a caller-supplied command.
 * {@link #createFrom} is the only creation path: it copies the snapshot's
 * labels/command/entrypoint/host config/aliases/healthcheck/exposed ports and
 * replaces only the environment map.</p>
 */
public interface InstanceRebuildGateway {

    /** Reads the full recreation input of the instance's container. */
    InstanceSnapshot snapshot(String instance);

    /** True when the image reference exists locally (the executor never pulls). */
    boolean imageExists(String imageRef);

    /** Immutable image id of an existing local image reference. */
    String imageId(String imageRef);

    /** Stops the container with a grace period, then renames it to {@code parkName}. */
    void stopAndPark(String instance, String parkName);

    /** Creates (not starts) the replacement container and returns its id. */
    String createFrom(InstanceSnapshot snapshot, Map<String, String> env, String imageRef,
                      String name);

    void start(String containerName);

    void remove(String containerName, boolean force);

    void rename(String containerName, String newName);

    /** Renames the parked container back and starts it. */
    void restore(String parkName, String originalName);

    /** {@code nginx -t} then {@code nginx -s reload} in the configured Nginx container. */
    void reloadNginx();
}
