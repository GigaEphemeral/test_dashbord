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
 * </ul>
 */
public record BenchLaunchPlan(String runId, String scenario, String containerName, String image,
                              List<String> argv, Map<String, String> env, List<Bind> binds,
                              Map<String, String> labels, String network,
                              String reportContainerPath, String reportHostPath) {

    public BenchLaunchPlan {
        argv = List.copyOf(argv);
        env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        binds = List.copyOf(binds);
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /** One {@code -v source:destination[:ro]} entry. */
    public record Bind(String source, String destination, boolean readOnly) {
    }
}
