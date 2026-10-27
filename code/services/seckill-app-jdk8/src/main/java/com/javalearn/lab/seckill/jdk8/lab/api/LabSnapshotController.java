package com.javalearn.lab.seckill.jdk8.lab.api;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.ThreadSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Internal snapshot API of the JDK 8 shell (design §9.3): the same two verbs the
 * JDK 21 shell exposes, so one console/D6 consumer can drive both runtimes.
 *
 * <p>Token-guarded by {@code LabTokenFilter} for {@code /lab/**} (Boot 2.7's
 * {@code FilterRegistrationBean}); no auth logic lives here. Nginx does not proxy
 * {@code /lab/**}, so only in-network callers reach it.</p>
 *
 * <ul>
 *   <li>{@code POST /lab/snapshot} — capture a bundle (three tool artefacts,
 *       metadata, template) and return its metadata. A failed or disabled capture
 *       is still 200 with {@code captured=false} and an {@code error}.</li>
 *   <li>{@code GET /lab/snapshot} — list the bundles currently on disk (folder,
 *       UTC timestamp, trigger, per-file sizes), newest first.</li>
 * </ul>
 */
@RestController
@RequestMapping("/lab")
public class LabSnapshotController {

    private final ThreadSnapshotService snapshots;

    public LabSnapshotController(ThreadSnapshotService snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @PostMapping("/snapshot")
    public Result<SnapshotView> capture() {
        return Result.success(snapshots.capture());
    }

    @GetMapping("/snapshot")
    public Result<List<SnapshotBundleView>> list() {
        return Result.success(snapshots.listBundles());
    }
}
