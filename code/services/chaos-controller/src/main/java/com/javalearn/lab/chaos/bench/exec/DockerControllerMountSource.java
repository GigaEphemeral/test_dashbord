package com.javalearn.lab.chaos.bench.exec;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;

import java.util.List;

/**
 * Docker-backed {@link BenchScriptsHostDirResolver.MountSource}: reads the
 * controller's own container through {@code inspectContainerCmd}.
 *
 * <p>Only mount metadata is read; nothing is created, started or removed. Both
 * "container unknown" and "daemon unreachable" become
 * {@link BenchUnavailableException} (503/50303) because either one makes the
 * host k6 directory unresolvable.</p>
 */
public class DockerControllerMountSource implements BenchScriptsHostDirResolver.MountSource {

    private final DockerClient dockerClient;

    public DockerControllerMountSource(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public List<BenchScriptsHostDirResolver.Mount> mounts(String containerName) {
        InspectContainerResponse inspected;
        try {
            inspected = dockerClient.inspectContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            throw new BenchUnavailableException("controller container '" + containerName
                    + "' not found; set LAB_BENCH_EXEC_CONTROLLER_CONTAINER to the real name or "
                    + "set LAB_BENCH_EXEC_SCRIPTS_HOST_DIR explicitly", e);
        } catch (DockerException e) {
            throw new BenchUnavailableException("Docker daemon unavailable while inspecting "
                    + "controller container '" + containerName + "'", e);
        } catch (RuntimeException e) {
            throw new BenchUnavailableException("Docker daemon unavailable while inspecting "
                    + "controller container '" + containerName + "'", e);
        }
        List<InspectContainerResponse.Mount> mounts = inspected.getMounts();
        if (mounts == null) {
            return List.of();
        }
        return mounts.stream()
                .map(mount -> new BenchScriptsHostDirResolver.Mount(mount.getSource(),
                        mount.getDestination() == null ? null : mount.getDestination().getPath()))
                .toList();
    }
}
