package com.javalearn.lab.chaos.bench.exec;

import com.javalearn.lab.chaos.error.BenchUnavailableException;

import java.util.List;

/**
 * Stage2 B2-02b-1: resolves the <em>host</em> directory behind the controller's
 * read-only k6 mount.
 *
 * <p>The controller runs in a container. {@code /app/k6} is a bind mount, but
 * the Docker daemon creates the new k6 container from the host's view, so the
 * plan must carry the host source path, never {@code /app/k6} itself. Two
 * sources are honoured, in this order:</p>
 * <ol>
 *   <li>{@code lab.bench.exec.scripts-host-dir}
 *       ({@code LAB_BENCH_EXEC_SCRIPTS_HOST_DIR}) when non-blank - an explicit
 *       operator override;</li>
 *   <li>the {@code Mount.Source} whose {@code Mount.Destination} equals
 *       {@code lab.bench.exec.controller-k6-mount} on the controller container
 *       named by {@code lab.bench.exec.controller-container}.</li>
 * </ol>
 *
 * <p>Every failure is {@link BenchUnavailableException} (503/50303) with an
 * actionable message: controller container unknown, no matching mount,
 * multiple matching mounts, or a blank source. This resolver never falls back
 * to an empty path or to the in-container path - a wrong bind source silently
 * launches k6 without its scripts.</p>
 */
public class BenchScriptsHostDirResolver {

    /** Minimal Docker view: the controller container's mounts. */
    public interface MountSource {

        /**
         * @return every mount of the named container (source/destination)
         * @throws BenchUnavailableException when the container or daemon is unavailable
         */
        List<Mount> mounts(String containerName);
    }

    /** One Docker mount, already flattened to strings. */
    public record Mount(String source, String destination) {
    }

    private final BenchExecProperties properties;
    private final MountSource mountSource;

    public BenchScriptsHostDirResolver(BenchExecProperties properties, MountSource mountSource) {
        this.properties = properties;
        this.mountSource = mountSource;
    }

    /**
     * @return the host directory to bind at {@code scripts-mount}
     * @throws BenchUnavailableException when the host path cannot be resolved
     */
    public String resolve() {
        String override = properties.getScriptsHostDir();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }

        String container = properties.getControllerContainer();
        String mount = properties.getControllerK6Mount();
        if (container == null || container.isBlank()) {
            throw new BenchUnavailableException("lab.bench.exec.controller-container is blank: "
                    + "neither an explicit LAB_BENCH_EXEC_SCRIPTS_HOST_DIR nor a controller "
                    + "container to inspect is configured");
        }
        if (mount == null || mount.isBlank()) {
            throw new BenchUnavailableException("lab.bench.exec.controller-k6-mount is blank: "
                    + "set LAB_BENCH_EXEC_CONTROLLER_K6_MOUNT to the container path that is "
                    + "bind-mounted from the host k6 directory");
        }

        List<Mount> mounts = mountSource.mounts(container.trim());
        if (mounts == null) {
            mounts = List.of();
        }
        List<Mount> matches = mounts.stream()
                .filter(item -> samePath(item.destination(), mount))
                .toList();
        if (matches.isEmpty()) {
            throw new BenchUnavailableException("controller container '" + container + "' has no "
                    + "mount with destination '" + mount + "'; check the compose k6 mount "
                    + "(../k6:/app/k6) or set LAB_BENCH_EXEC_SCRIPTS_HOST_DIR explicitly");
        }
        if (matches.size() > 1) {
            throw new BenchUnavailableException("controller container '" + container + "' has "
                    + matches.size() + " mounts with destination '" + mount
                    + "'; set LAB_BENCH_EXEC_SCRIPTS_HOST_DIR explicitly to disambiguate");
        }
        String source = matches.get(0).source();
        if (source == null || source.isBlank()) {
            throw new BenchUnavailableException("controller container '" + container + "' reports a "
                    + "blank source for the mount at '" + mount + "'; refusing to bind an empty "
                    + "host directory (set LAB_BENCH_EXEC_SCRIPTS_HOST_DIR)");
        }
        return source.trim();
    }

    /** Compares container paths, tolerating one trailing slash. */
    private static boolean samePath(String left, String right) {
        return left != null && right != null
                && stripTrailingSlash(left.trim()).equals(stripTrailingSlash(right.trim()));
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
