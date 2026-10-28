package com.javalearn.lab.chaos.bench.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fully rendered, immutable k6 launch plan (Stage2 B2-02a). It is data only:
 * no Docker client is involved and no filesystem path is probed.
 *
 * <p>Every field is derived from whitelisted sources: the manifest scenario
 * ({@code script}, {@code env}, {@code params}), the configuration
 * ({@link BenchExecProperties}) and the run id. There is no field a caller can
 * supply verbatim for the image, command, mounts or script path, so an
 * arbitrary {@code --out}, script, volume or image cannot be expressed.</p>
 *
 * <ul>
 *   <li>{@code argv} — k6 arguments without a shell;</li>
 *   <li>{@code env} — only scenario {@code env} names plus the forced values;</li>
 *   <li>{@code binds} — scripts (read-only) and the reports volume (read-write);</li>
 *   <li>{@code reportContainerPath} — where k6 writes the summary INSIDE the
 *       container ({@code /reports/<runId>/<summary-file>}, 避坑 18);
 *       {@code reportHostPath} is the same path from the controller's view and
 *       is only useful once B2-02b copies/reads it through the volume.</li>
 *   <li>{@code traffic} — Stage2 B2-03a attribution: the requested traffic
 *       mode, the instance the run is pinned to (or {@code nginx} in
 *       {@code via-nginx} mode), the resolved {@code BASE_URL}, whether Nginx
 *       is bypassed, the {@code traffic_mixed} flag and the mirrored Nginx
 *       upstream weights (see {@link Traffic}).</li>
 * </ul>
 */
public record BenchLaunchPlan(String runId, String scenario, String containerName, String image,
                              List<String> argv, Map<String, String> env, List<Bind> binds,
                              Map<String, String> labels, String network,
                              String reportContainerPath, String reportHostPath,
                              Traffic traffic) {

    public BenchLaunchPlan {
        argv = List.copyOf(argv);
        env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        binds = List.copyOf(binds);
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /** One {@code -v source:destination[:ro]} entry. */
    public record Bind(String source, String destination, boolean readOnly) {
    }

    /**
     * Stage2 B2-03a traffic attribution (design §10.6).
     *
     * <p>{@code mode} is {@code via-nginx} or {@code target-instance};
     * {@code targetInstance} is the instance label k6 attaches to every
     * request ({@code nginx} when the run goes through the upstream);
     * {@code baseUrl} is the resolved {@code BASE_URL} (the Nginx entry or
     * {@code http://<target>:<port>}); {@code bypassNginx} is true only for
     * {@code target-instance}.</p>
     *
     * <p>{@code mixed} is always false: the run precheck stops the background
     * {@code jl-traffic} service before a controlled run, so this executor
     * cannot observe other traffic. It is recorded explicitly rather than
     * inferred - mixed-traffic detection is <em>not</em> implemented, and the
     * report must not claim otherwise.</p>
     *
     * <p>{@code upstream} mirrors the repository's Nginx configuration
     * ({@code code/lab/docker/nginx/conf.d/default.conf}) as
     * {@code app-1:8080=1,app-2:8080=1,app-3:8080=1}: every instance carries
     * the same weight (round-robin, {@code max_fails=2 fail_timeout=5s}). It is
     * derived from the configured target table and target port, so an operator
     * who edits {@code LAB_BENCH_EXEC_TARGET_INSTANCES} must keep the real
     * Nginx upstream in sync; the value is a record, not a live probe.</p>
     */
    public record Traffic(String mode, String targetInstance, String baseUrl, boolean bypassNginx,
                          boolean mixed, String upstream) {
    }
}
