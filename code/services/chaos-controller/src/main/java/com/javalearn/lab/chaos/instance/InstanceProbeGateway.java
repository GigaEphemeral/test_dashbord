package com.javalearn.lab.chaos.instance;

/**
 * One bounded HTTP GET used by the B4-02 health gates. Kept as a separate
 * interface so the orchestration tests run without Docker or a live app: the
 * fake returns canned {@link ProbeResult}s keyed by URL.
 */
public interface InstanceProbeGateway {

    /**
     * @param url            absolute http(s) URL
     * @param labToken       when non-null, sent as {@code X-Lab-Token}
     * @param timeoutSeconds per-request budget
     */
    ProbeResult get(String url, String labToken, int timeoutSeconds);

    /**
     * @param status  HTTP status, or {@code -1} when the call failed
     * @param body    response body (bounded by the implementation)
     * @param error   transport error description, or {@code null}
     */
    record ProbeResult(int status, String body, String error) {

        public boolean ok() {
            return status >= 200 && status < 300 && error == null;
        }

        public String describe() {
            return error != null ? error : "HTTP " + status + (body == null || body.isBlank()
                    ? "" : ": " + snippet(body));
        }

        private static String snippet(String body) {
            String collapsed = body.replaceAll("\\s+", " ").trim();
            return collapsed.length() > 160 ? collapsed.substring(0, 160) + "..." : collapsed;
        }
    }
}
