package com.javalearn.lab.chaos.toxiproxy;

import java.util.List;

/**
 * Thin client for the Toxiproxy v2 control API. Only the five operations the
 * chaos lab needs are exposed; everything is addressed by proxy name from
 * {@code chaos.toxiproxy.proxies}.
 *
 * <p>Implementations translate Toxiproxy's HTTP errors into the controller's
 * exception vocabulary: 404 → {@code ToxiproxyNotFoundException}, 409 →
 * {@code ToxiproxyConflictException}, 400 → {@code InvalidToxiproxyRequestException},
 * transport/5xx → {@code ToxiproxyUnavailableException}. Keeping HTTP out of
 * the service makes the whole action layer testable without Docker or a live
 * proxy.</p>
 */
public interface ToxiproxyGateway {

    /** Create one toxic and return Toxiproxy's view of it. */
    ToxicView addToxic(String proxy, ToxicSpec toxic);

    /** Delete one toxic; a missing toxic is a 404. */
    void removeToxic(String proxy, String toxicName);

    /**
     * Remove every toxic currently attached to the proxy. Toxiproxy has no
     * per-proxy reset endpoint, so this is list + delete; toxics that disappear
     * concurrently are tolerated.
     *
     * @return number of toxics actually deleted
     */
    int resetProxy(String proxy);

    /** Enable or disable (断网) the proxy without deleting its toxics. */
    void setProxyEnabled(String proxy, boolean enabled);

    /** List the toxics currently attached to the proxy. */
    List<ToxicView> listToxics(String proxy);

    /** Read proxy state (enabled flag + toxics) in one call. */
    ProxyView getProxy(String proxy);
}
