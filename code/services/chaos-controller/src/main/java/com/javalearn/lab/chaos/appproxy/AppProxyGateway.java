package com.javalearn.lab.chaos.appproxy;

/**
 * Transport seam for the thread-lab proxy. The production implementation uses
 * the JDK {@code HttpClient}; tests inject a recording stub so the allow-list
 * logic can be verified without a network.
 */
public interface AppProxyGateway {

    /**
     * Sends one already-validated call to {@code instance}.
     *
     * @param instance allow-listed app instance name
     * @param method   {@code GET} or {@code POST}
     * @param path     allow-listed path beginning with {@code /lab/thread}
     * @param query    raw query string (the part after {@code ?}), forwarded
     *                 verbatim without re-encoding or re-interpretation;
     *                 {@code null} or empty means "no query"
     * @param body     JSON body for {@code POST /lab/thread/fault}; {@code null} otherwise
     * @return the upstream status and its (JSON-validated) body
     */
    ProxyResponse exchange(String instance, String method, String path, String query, byte[] body);
}
