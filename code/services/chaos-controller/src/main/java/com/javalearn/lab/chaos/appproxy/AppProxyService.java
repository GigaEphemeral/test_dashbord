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
 * <p>The allow-list is deliberately explicit: the thread-lab fault modes,
 * lifecycle steps and the cache-lab endpoints are spelled out, so a new app
 * endpoint is unreachable until it is added here on purpose. Anchored patterns
 * reject extra segments, traversal ({@code ..}) and percent-encoded tricks;
 * anything outside the list is a 404/40401, even when it starts with
 * {@code /lab/}.</p>
 *
 * <p><strong>Exact allow-list</strong> (the only paths this proxy forwards):</p>
 * <ul>
 *   <li>Thread lab, GET: {@code /lab/thread/state}, {@code /lab/thread/lifecycle}</li>
 *   <li>Thread lab, POST: {@code /lab/thread/fault},
 *       {@code /lab/thread/stop-all}, {@code /lab/thread/lifecycle/start|step|freeze|resume|reset},
 *       {@code /lab/thread/snapshot}, and
 *       {@code /lab/thread/fault/{deadlock|stuck|cpu-burn|heap-leak|oom|thread-leak|starvation}/{stop|pause|resume}}</li>
 *   <li>Cache lab, GET: {@code /lab/cache/state}, {@code /lab/cache/preheat}</li>
 *   <li>Cache lab, POST: {@code /lab/cache/mode} (JSON body),
 *       {@code /lab/cache/seed}, {@code /lab/cache/preheat}</li>
 * </ul>
 * <p>Only {@code POST /lab/thread/fault} and {@code POST /lab/cache/mode} carry
 * a request body; every other allowed POST is forwarded without one.</p>
 */
public class AppProxyService {

    private static final String THREAD = "/lab/thread";
    private static final String CACHE = "/lab/cache";

    private static final Set<String> THREAD_GET_PATHS = Set.of(
            THREAD + "/state",
            THREAD + "/lifecycle");

    /** Thread-lab POST endpoints; only {@code /fault} carries a body. */
    private static final Set<String> THREAD_POST_PATHS = Set.of(
            THREAD + "/fault",
            THREAD + "/stop-all",
            THREAD + "/lifecycle/start",
            THREAD + "/lifecycle/step",
            THREAD + "/lifecycle/freeze",
            THREAD + "/lifecycle/resume",
            THREAD + "/lifecycle/reset",
            THREAD + "/snapshot");

    /**
     * Cache-lab reads: the full state (mode + strategy counters + dataset) and
     * the preheat snapshot. {@code GET /lab/cache/mode} is deliberately absent:
     * the console reads the mode from {@code /state}, so the proxy exposes one
     * read contract instead of two.
     */
    private static final Set<String> CACHE_GET_PATHS = Set.of(
            CACHE + "/state",
            CACHE + "/preheat");

    /** Cache-lab writes; only {@code /mode} carries the {@code {"mode": ...}} body. */
    private static final Set<String> CACHE_POST_PATHS = Set.of(
            CACHE + "/mode",
            CACHE + "/seed",
            CACHE + "/preheat");

    /** The complete set of POST paths whose body is relayed verbatim. */
    private static final Set<String> BODY_PATHS = Set.of(
            THREAD + "/fault",
            CACHE + "/mode");

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
     *                 and {@code POST /lab/cache/mode}
     */
    public ProxyResponse forward(String instance, String method, String path, byte[] body) {
        if (instance == null || !config.getInstances().contains(instance)) {
            throw new UnknownAppInstanceException("Unknown app instance '" + instance
                    + "'; allowed instances: " + config.getInstances());
        }
        String verb = method == null ? "" : method.toUpperCase();
        if (!isAllowed(verb, path)) {
            throw new AppPathNotAllowedException("Path is not in the app proxy allow-list: "
                    + verb + " " + path);
        }
        if (body != null && body.length > config.getMaxRequestBytes()) {
            throw new InvalidAppProxyRequestException("Request body is " + body.length
                    + " bytes; the app proxy accepts at most "
                    + config.getMaxRequestBytes() + " bytes");
        }
        byte[] payload = "POST".equals(verb) && BODY_PATHS.contains(path)
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
            return THREAD_GET_PATHS.contains(path) || CACHE_GET_PATHS.contains(path);
        }
        if ("POST".equals(method)) {
            return THREAD_POST_PATHS.contains(path)
                    || CACHE_POST_PATHS.contains(path)
                    || FAULT_ACTION.matcher(path).matches();
        }
        return false;
    }
}
