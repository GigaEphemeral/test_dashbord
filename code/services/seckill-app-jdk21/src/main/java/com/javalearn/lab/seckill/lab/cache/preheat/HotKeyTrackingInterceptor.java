package com.javalearn.lab.seckill.lab.cache.preheat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Feeds the per-instance {@link HotKeyTracker} from the product read endpoint.
 *
 * <p>Hooked at MVC instead of inside {@code @Cacheable} on purpose: the cached
 * method body only runs on an L1+L2 miss, while hot-key detection must see
 * every read — including the cache hits it is supposed to protect. The
 * interceptor is registered for {@code GET /api/products/*} only and never
 * changes the response: a tracking failure is logged and the request proceeds.</p>
 */
public final class HotKeyTrackingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HotKeyTrackingInterceptor.class);

    static final String PRODUCT_PATH_PREFIX = "/api/products/";

    private final HotKeyTracker tracker;

    public HotKeyTrackingInterceptor(HotKeyTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(PRODUCT_PATH_PREFIX)) {
            return true;
        }
        String suffix = path.substring(PRODUCT_PATH_PREFIX.length());
        if (suffix.isEmpty() || suffix.indexOf('/') >= 0) {
            return true;
        }
        long productId;
        try {
            productId = Long.parseLong(suffix);
        } catch (NumberFormatException ex) {
            // Not a product id; the controller will answer 400/404 as usual.
            return true;
        }
        if (productId <= 0L) {
            return true;
        }
        try {
            tracker.record(productId);
        } catch (RuntimeException ex) {
            // Detection is advisory: it must never turn a product read into a 500.
            log.warn("Hot-key tracking failed for product {}: {}", productId, ex.toString());
        }
        return true;
    }
}
