package com.javalearn.lab.chaos.appproxy;

/**
 * One upstream answer relayed to the console.
 *
 * @param status the upstream HTTP status (2xx/4xx/5xx JSON envelopes are relayed verbatim)
 * @param body   the validated JSON body, exactly as the app wrote it
 */
public record ProxyResponse(int status, String body) {
}
