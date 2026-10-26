package com.javalearn.lab.chaos.gateway;

import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.model.ExecResult;

import java.util.List;

/**
 * Lab container operations. Every mutating call must enforce the
 * {@code lab=java-learn} label before touching the Docker daemon.
 */
public interface ContainerGateway {

    String SIGSTOP = "SIGSTOP";
    String SIGCONT = "SIGCONT";

    List<ContainerView> listLabContainers();

    String tailLogs(String containerName, int tail);

    /**
     * Inspects a whitelisted container.
     *
     * @throws com.javalearn.lab.chaos.error.ContainerNotFoundException when unknown
     * @throws com.javalearn.lab.chaos.error.ContainerNotAllowedException when not a lab container
     */
    ContainerView inspectLabContainer(String containerName);

    /** Docker stop (manual stop so {@code unless-stopped} does not restart it). */
    void stop(String containerName, int graceSeconds);

    void start(String containerName);

    void restart(String containerName, int graceSeconds);

    /** Sends a signal to the container's init process; only SIGSTOP/SIGCONT are allowed. */
    void signal(String containerName, String signal);

    /**
     * Runs one bounded command inside a whitelisted container (read-only use:
     * the MySQL cluster probe feeds SQL on stdin and never mutates anything).
     *
     * <p>Secrets travel as exec environment variables, never as argv: the mysql
     * client reads {@code MYSQL_PWD} from its own environment, so neither the
     * query nor the credential is visible in the container's process list.</p>
     *
     * @param containerName whitelisted container to exec into
     * @param command       argv, already rendered (no shell interpolation)
     * @param env           {@code KEY=VALUE} entries for the exec process; may be empty
     * @param stdin         text piped to the process, or {@code null} to close stdin
     * @param timeoutSeconds upper bound for the Java-side wait
     * @throws com.javalearn.lab.chaos.error.ContainerNotFoundException  when unknown
     * @throws com.javalearn.lab.chaos.error.ContainerNotAllowedException when not a lab container
     * @throws com.javalearn.lab.chaos.error.DockerUnavailableException when the exec cannot be started
     */
    ExecResult execInContainer(String containerName,
                               List<String> command,
                               List<String> env,
                               String stdin,
                               int timeoutSeconds);
}
