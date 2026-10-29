package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.auth.TokenFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JDK-HttpClient probe used after a rebuild (same HTTP stack as the app proxy
 * and Prometheus clients, so B4-02 adds no dependency).
 *
 * <p>Responses are bounded to 8 KiB: these are health answers, not reports. The
 * token travels only in the {@code X-Lab-Token} header and is never logged or
 * included in an error message.</p>
 */
public class HttpInstanceProbeGateway implements InstanceProbeGateway {

    private static final int MAX_BODY_BYTES = 8192;

    private final HttpClient httpClient;

    public HttpInstanceProbeGateway() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public ProbeResult get(String url, String labToken, int timeoutSeconds) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Accept", "application/json")
                    .GET();
            if (labToken != null && !labToken.isBlank()) {
                builder.header(TokenFilter.TOKEN_HEADER, labToken);
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body() == null ? new byte[0] : response.body();
            String text = new String(body, 0, Math.min(body.length, MAX_BODY_BYTES),
                    StandardCharsets.UTF_8);
            return new ProbeResult(response.statusCode(), text, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(-1, null, "interrupted while calling " + url);
        } catch (IOException | RuntimeException e) {
            return new ProbeResult(-1, null, "unreachable at " + url + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }
}
