package com.javalearn.lab.chaos.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Guards every lab API call except the observability endpoints.
 *
 * <p>The Docker socket is mounted read-write for later milestones, so the token
 * is the first server-side gate; the label whitelist in the gateway is the
 * second. The token value is never logged.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Lab-Token";

    private static final Logger log = LoggerFactory.getLogger(TokenFilter.class);
    private static final Set<String> PUBLIC_PATHS = Set.of("/health", "/metrics", "/info", "/actuator");
    private static final String ACTUATOR_PREFIX = "/actuator/";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public TokenFilter(@Value("${lab.token}") String token, ObjectMapper objectMapper) {
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || PUBLIC_PATHS.contains(path)
                || path.startsWith(ACTUATOR_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided != null && MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }

        // Never log the supplied header: it may be a mistyped real token.
        log.debug("Rejected unauthenticated request for {}", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.failure(CommonErrorCode.UNAUTHORIZED));
    }
}
