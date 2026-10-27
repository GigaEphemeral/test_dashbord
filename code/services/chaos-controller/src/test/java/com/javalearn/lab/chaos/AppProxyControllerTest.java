package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.error.AppPathNotAllowedException;
import com.javalearn.lab.chaos.error.AppProxyRequestTooLargeException;
import com.javalearn.lab.chaos.error.AppProxyResponseTooLargeException;
import com.javalearn.lab.chaos.error.AppResponseNotJsonException;
import com.javalearn.lab.chaos.error.AppUnreachableException;
import com.javalearn.lab.chaos.error.UnknownAppInstanceException;
import com.javalearn.lab.chaos.web.AppProxyController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AppProxyController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class AppProxyControllerTest {

    private static final String STATE_JSON =
            "{\"code\":\"0\",\"message\":\"OK\",\"data\":[{\"mode\":\"cpu-burn\"}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppProxyService appProxyService;

    @BeforeEach
    void setUp() {
        when(appProxyService.maxRequestBytes()).thenReturn(8192);
    }

    @Test
    void getRelaysTheUpstreamStatusAndBody() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(200, STATE_JSON));

        mockMvc.perform(get("/chaos/app/app-2/lab/thread/state"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].mode").value("cpu-burn"))
                .andExpect(header().doesNotExist("X-Lab-Token"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-2"),
                org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("/lab/thread/state"), query.capture(), body.capture());
        assertThat(query.getValue()).isNull();
        assertThat(body.getValue()).isEmpty();
    }

    @Test
    void getForwardsTheRawQueryStringVerbatimToTheService() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(200, STATE_JSON));

        mockMvc.perform(get("/chaos/app/app-1/lab/thread/state?cursor=v2:8&limit=50&tail=20"))
                .andExpect(status().isOk());

        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-1"),
                org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("/lab/thread/state"),
                org.mockito.ArgumentMatchers.eq("cursor=v2:8&limit=50&tail=20"), any());
    }

    @Test
    void postForwardsTheRawFaultJsonBody() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(200, STATE_JSON));
        String request = "{\"mode\":\"cpu-burn\",\"hold\":\"auto\",\"ttlSeconds\":5}";

        mockMvc.perform(post("/chaos/app/app-1/lab/thread/fault")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-1"),
                org.mockito.ArgumentMatchers.eq("POST"),
                org.mockito.ArgumentMatchers.eq("/lab/thread/fault"), any(), body.capture());
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void postForwardsTheRawCacheModeJsonBody() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(200, "{\"code\":\"0\",\"data\":{\"mode\":\"null-cache\"}}"));
        String request = "{\"mode\":\"null-cache\"}";

        mockMvc.perform(post("/chaos/app/app-2/lab/cache/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("null-cache"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-2"),
                org.mockito.ArgumentMatchers.eq("POST"),
                org.mockito.ArgumentMatchers.eq("/lab/cache/mode"), any(), body.capture());
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void postWithoutABodyStillReachesTheService() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(200, "{\"code\":\"0\",\"data\":null}"));

        mockMvc.perform(post("/chaos/app/app-1/lab/thread/stop-all"))
                .andExpect(status().isOk());

        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-1"),
                org.mockito.ArgumentMatchers.eq("POST"),
                org.mockito.ArgumentMatchers.eq("/lab/thread/stop-all"), any(), any());
    }

    @Test
    void unknownInstanceMapsTo404() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new UnknownAppInstanceException("Unknown app instance 'app-9'"));

        mockMvc.perform(get("/chaos/app/app-9/lab/thread/state"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("app-9")));
    }

    @Test
    void offListPathMapsTo404() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new AppPathNotAllowedException(
                        "Path is not in the thread-lab proxy allow-list: GET /lab/toggles"));

        mockMvc.perform(get("/chaos/app/app-1/lab/toggles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("allow-list")));
    }

    @Test
    void offListPathKeepsIts404WhenAQueryStringIsPresent() throws Exception {
        // The query must never widen the allow-list: B3's /lab/cache/keys is
        // unreachable through the proxy until the path is added on purpose.
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new AppPathNotAllowedException(
                        "Path is not in the app proxy allow-list: GET /lab/cache/keys"));

        mockMvc.perform(get("/chaos/app/app-1/lab/cache/keys?cursor=v2:8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void unreachableAppMapsTo503() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new AppUnreachableException("app-1 is unreachable at http://app-1:8080"));

        mockMvc.perform(get("/chaos/app/app-1/lab/thread/state"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50301"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unreachable")));
    }

    @Test
    void nonJsonAnswerMapsTo502() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new AppResponseNotJsonException(
                        "app-1 returned a non-JSON response (HTTP 200, content-type text/html)"));

        mockMvc.perform(get("/chaos/app/app-1/lab/thread/state"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("50201"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("non-JSON")));
    }

    @Test
    void oversizedUpstreamAnswerMapsTo502WithItsOwnCode() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new AppProxyResponseTooLargeException(
                        "app-1 returned 4096 bytes; the app proxy relays at most 512 bytes for GET /lab/cache/state"));

        mockMvc.perform(get("/chaos/app/app-1/lab/cache/state"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("50202"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("at most 512")));
    }

    @Test
    void unsupportedVerbMapsTo405() throws Exception {
        mockMvc.perform(delete("/chaos/app/app-1/lab/thread/state"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("40501"));

        verify(appProxyService, never()).forward(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void oversizedBodyIsRejectedBeforeForwarding() throws Exception {
        when(appProxyService.maxRequestBytes()).thenReturn(4);

        mockMvc.perform(post("/chaos/app/app-1/lab/thread/fault")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("12345"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("41301"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("exceeds 4 bytes")));

        verify(appProxyService, never()).forward(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void bodyOverTheCapFromTheServiceAlsoMapsTo413() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new AppProxyRequestTooLargeException(
                        "Request body is 999 bytes; the app proxy accepts at most 8192 bytes"));

        mockMvc.perform(post("/chaos/app/app-1/lab/cache/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("41301"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("at most 8192")));
    }
}
