package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit data must never carry credentials, and the audit must stay useful: the
 * shape of a failure is kept, the secret is masked, and a sensitive summary key
 * is dropped instead of stored.
 */
class AuditSanitizerTest {

    @Test
    void masksPasswordTokenAndApiKeyAssignments() {
        assertThat(AuditSanitizer.scrub("connect failed: password=hunter2 (user root)"))
                .isEqualTo("connect failed: password=*** (user root)");
        assertThat(AuditSanitizer.scrub("token: abc.def123"))
                .isEqualTo("token: ***");
        assertThat(AuditSanitizer.scrub("api_key='s3cr3t' rejected"))
                .isEqualTo("api_key=*** rejected");
        assertThat(AuditSanitizer.scrub("Authorization: Bearer eyJhbGciOi.payload.sig"))
                .contains("Bearer ***")
                .doesNotContain("eyJhbGciOi");
    }

    @Test
    void masksCredentialsEmbeddedInJdbcUrlsAndKeepsNormalText() {
        assertThat(AuditSanitizer.scrub("jdbc:mysql://root:labroot@mysql:3306/lab_ledger"))
                .isEqualTo("jdbc:mysql://root:***@mysql:3306/lab_ledger");
        assertThat(AuditSanitizer.scrub("container jl-app-1 is not running"))
                .isEqualTo("container jl-app-1 is not running");
        assertThat(AuditSanitizer.scrub(null)).isNull();
    }

    @Test
    void describeBuildsATypeAndMaskedMessageWithinTheColumnWidth() {
        String described = AuditSanitizer.describe(new IllegalStateException(
                "docker stop failed token=" + "x".repeat(2000)));

        assertThat(described).startsWith("IllegalStateException: docker stop failed token=***");
        assertThat(described).hasSizeLessThanOrEqualTo(AuditSanitizer.MAX_ERROR_LENGTH);
    }

    @Test
    void summaryDropsSensitiveKeysAndScrubsValuesWithoutMutatingTheInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("container", "jl-app-1");
        input.put("password", "hunter2");
        input.put("authorization", "Bearer abc");
        input.put("note", "token=abc123 applied");
        input.put("ttlSeconds", 30);

        Map<String, Object> clean = AuditSanitizer.scrubSummary(input);

        assertThat(clean).containsEntry("container", "jl-app-1")
                .containsEntry("ttlSeconds", 30)
                .containsEntry("note", "token=*** applied")
                .doesNotContainKeys("password", "authorization");
        assertThat(input).containsKey("password");
    }
}
