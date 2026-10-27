package com.javalearn.lab.chaos.appproxy;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppPathNotAllowedException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
import com.javalearn.lab.chaos.error.UnknownAppInstanceException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates every console call before it reaches an app instance.
 *
 * <p>The allow-list is deliberately explicit: the seven fault modes and the
 * lifecycle steps are spelled out, so a new app endpoint is unreachable until
 * it is added here on purpose. Anchored patterns reject extra segments,
 * traversal ({@code ..}) and percent-encoded tricks; anything outside the list
 * is a 404/40401, even when it starts with {@code /lab/}.</p>
 */
public class AppProxyService {

    private static final String THREAD = "/lab/thread";

    private static final Set<String> GET_PATHS = Set.of(
            THREAD + "/state",
            THREAD + "/lifecycle");

    /** POST endpoints without a body, plus the one endpoint that carries the fault JSON. */
    private static final Set<String> POST_PATHS = Set.of(
            THREAD + "/fault",
            THREAD + "/stop-all",
            THREAD + "/lifecycle/start",
            THREAD + "/lifecycle/step",
            THREAD + "/lifecycle/freeze",
            THREAD + "/lifecycle/resume",
            THREAD + "/lifecycle/reset",
            THREAD + "/snapshot");

    private static final String FAULT_BODY_PATH = THREAD + "/fault";

    /** Only the seven documented fault modes may appear in the path. */
    private static final Pattern FAULT_ACTION = Pattern.compile(
            "^" + THREAD + "/fault/(deadlock|stuck|cpu-burn|heap-leak|oom|thread-leak|starvation)"
                    + "/(stop|pause|resume)$");

    private final ChaosProperties.AppProxy config;
    private final AppProxyGateway gateway;

    public AppProxyService(ChaosProperties.AppProxy config, AppProxyGateway gateway) {
        this.config = config;
        this.gateway = gateway;
    }

    /**
     * @param instance allow-listed app instance
     * @param method   {@code GET} or {@code POST} (the controller maps only these)
     * @param path     raw request path below {@code /chaos/app/{instance}}
     * @param body     raw request body; only forwarded for {@code POST /lab/thread/fault}
     */
    public ProxyResponse forward(String instance, String method, String path, byte[] body) {
        if (instance == null || !config.getInstances().contains(instance)) {
            throw new UnknownAppInstanceException("Unknown app instance '" + instance
                    + "'; allowed instances: " + config.getInstances());
        }
        String verb = method == null ? "" : method.toUpperCase();
        if (!isAllowed(verb, path)) {
            throw new AppPathNotAllowedException("Path is not in the thread-lab proxy allow-list: "
                    + verb + " " + path);
        }
        if (body != null && body.length > config.getMaxRequestBytes()) {
            throw new InvalidAppProxyRequestException("Request body is " + body.length
                    + " bytes; the thread-lab proxy accepts at most "
                    + config.getMaxRequestBytes() + " bytes");
        }
        byte[] payload = "POST".equals(verb) && FAULT_BODY_PATH.equals(path)
                && body != null && body.length > 0 ? body : null;
        return gateway.exchange(instance, verb, path, payload);
    }

    /** Body cap exposed to the controller so it never buffers an unbounded request. */
    public int maxRequestBytes() {
        return config.getMaxRequestBytes();
    }

    private static boolean isAllowed(String method, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if ("GET".equals(method)) {
            return GET_PATHS.contains(path);
        }
        if ("POST".equals(method)) {
            return POST_PATHS.contains(path) || FAULT_ACTION.matcher(path).matches();
        }
        return false;
    }
}
