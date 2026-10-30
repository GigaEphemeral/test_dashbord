package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The tracking hook sees every {@code GET /api/products/{id}} — including
 * cache hits — without ever affecting the response.
 */
class HotKeyTrackingInterceptorTest {

    private final HotKeyTracker tracker = mock(HotKeyTracker.class);
    private final HotKeyTrackingInterceptor interceptor = new HotKeyTrackingInterceptor(tracker);

    @Test
    void aProductReadIsRecorded() {
        assertThat(interceptor.preHandle(request("GET", "/api/products/42"),
                new MockHttpServletResponse(), new Object())).isTrue();

        verify(tracker).record(42L);
    }

    @Test
    void aContextPathIsNotMistakenForAProductId() {
        MockHttpServletRequest request = request("GET", "/app/api/products/7");
        request.setContextPath("/app");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();

        verify(tracker).record(7L);
    }

    @Test
    void nonReadsAndNonNumericPathsAreIgnored() {
        interceptor.preHandle(request("POST", "/api/products/42"),
                new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("GET", "/api/products/not-a-number"),
                new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("GET", "/api/products/42/reviews"),
                new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("GET", "/api/products/-1"),
                new MockHttpServletResponse(), new Object());

        verify(tracker, never()).record(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aTrackerFailureNeverBreaksTheRead() {
        doThrow(new IllegalStateException("boom")).when(tracker).record(42L);

        assertThat(interceptor.preHandle(request("GET", "/api/products/42"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
