package com.javalearn.lab.seckill.jdk8.lab.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.lab.config.LabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Guards the JDK 8 internal lab API ({@code /lab/**}) with the shared
 * {@code X-Lab-Token} (design §9.3).
 *
 * <p>The shell had no lab filter before the snapshot endpoint; this is the same
 * contract as the JDK 21 {@code LabTokenFilter}, ported to the Boot 2.7
 * {@code javax.servlet} API: constant-time comparison ({@link MessageDigest#isEqual}),
 * neither the expected nor the supplied token is ever logged, and the rejection is
 * the shared {@code 40101} envelope. Nginx does not proxy {@code /lab/**}, so this
 * filter is the server-side gate for in-network callers.</p>
 *
 * <p>Registered only by {@code LabSnapshotConfig} for {@code /lab/*} (not a
 * component), so MVC slice tests and the public {@code /api/**} path never load it.</p>
 */
public class LabTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Lab-Token";

    private static final Logger log = LoggerFactory.getLogger(LabTokenFilter.class);

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public LabTokenFilter(LabProperties properties, ObjectMapper objectMapper) {
        Objects.requireNonNull(properties, "properties");
        this.expectedToken = properties.getToken().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !isLabPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided != null
                && MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("Rejected unauthenticated lab request for {}", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.failure(CommonErrorCode.UNAUTHORIZED));
    }

    private static boolean isLabPath(String uri) {
        return "/lab".equals(uri) || uri.startsWith("/lab/");
    }
}
