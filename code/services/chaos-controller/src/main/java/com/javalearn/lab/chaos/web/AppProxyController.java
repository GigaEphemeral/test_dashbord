package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.error.AppPathNotAllowedException;
import com.javalearn.lab.chaos.error.AppProxyRequestTooLargeException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Narrow proxy to one app instance's thread lab (design §9.1/§9.2/§9.3).
 *
 * <p>Path shape: {@code /chaos/app/{instance}/lab/thread/...}. The console
 * reaches it through Nginx as {@code /lab-api/chaos/app/...}; only GET and POST
 * are mapped, so any other verb is answered 405/40501 by the shared advice. The
 * instance and path allow-lists live in {@link AppProxyService}; this class only
 * extracts the raw path and relays the app's JSON envelope verbatim.</p>
 *
 * <p>{@code X-Lab-Token} is added by the HTTP gateway from the controller's own
 * configuration. The caller's token is validated by the shared
 * {@code TokenFilter} and is never copied into the upstream request or the
 * response.</p>
 */
@RestController
@RequestMapping("/chaos/app/{instance}")
public class AppProxyController {

    private static final String PREFIX = "/chaos/app/";
    /** Explicit charset: the upstream envelope is UTF-8 and is relayed as a String. */
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final AppProxyService service;

    public AppProxyController(AppProxyService service) {
        this.service = service;
    }

    @GetMapping("/**")
    public ResponseEntity<String> get(@PathVariable String instance, HttpServletRequest request) {
        return forward(instance, "GET", request, new byte[0]);
    }

    @PostMapping("/**")
    public ResponseEntity<String> post(@PathVariable String instance, HttpServletRequest request) {
        return forward(instance, "POST", request, readBody(request));
    }

    private ResponseEntity<String> forward(String instance, String method,
                                           HttpServletRequest request, byte[] body) {
        String uri = request.getRequestURI();
        String expectedPrefix = PREFIX + instance;
        if (!uri.startsWith(expectedPrefix)) {
            // Encoded or re-segmented instance: never derive a path from a URI
            // that does not start with the decoded instance itself.
            throw new AppPathNotAllowedException("Unsupported proxy path: " + method + " " + uri);
        }
        String path = uri.substring(expectedPrefix.length());
        // Raw request target query (already percent-encoded on the wire); the
        // proxy passes it through verbatim so callers can paginate
        // (B3: /lab/cache/keys?cursor=...). Null/empty means "no query".
        String query = request.getQueryString();
        ProxyResponse response = service.forward(instance, method, path, query, body);
        return ResponseEntity.status(response.status())
                .contentType(JSON_UTF8)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response.body() == null ? "" : response.body());
    }

    private byte[] readBody(HttpServletRequest request) {
        int limit = Math.max(1, service.maxRequestBytes());
        try {
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) {
                throw new AppProxyRequestTooLargeException("Request body exceeds "
                        + limit + " bytes");
            }
            return body;
        } catch (IOException e) {
            throw new InvalidAppProxyRequestException("Could not read the request body: " + e.getMessage());
        }
    }
}
