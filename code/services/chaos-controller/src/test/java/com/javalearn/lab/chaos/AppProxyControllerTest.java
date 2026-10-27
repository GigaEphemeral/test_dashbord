package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.error.AppPathNotAllowedException;
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
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ProxyResponse(200, STATE_JSON));

        mockMvc.perform(get("/chaos/app/app-2/lab/thread/state"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].mode").value("cpu-burn"))
                .andExpect(header().doesNotExist("X-Lab-Token"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-2"),
                org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("/lab/thread/state"), body.capture());
        assertThat(body.getValue()).isEmpty();
    }

    @Test
    void postForwardsTheRawFaultJsonBody() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
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
                org.mockito.ArgumentMatchers.eq("/lab/thread/fault"), body.capture());
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(request);
    }

    @Test
    void postWithoutABodyStillReachesTheService() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ProxyResponse(200, "{\"code\":\"0\",\"data\":null}"));

        mockMvc.perform(post("/chaos/app/app-1/lab/thread/stop-all"))
                .andExpect(status().isOk());

        verify(appProxyService).forward(org.mockito.ArgumentMatchers.eq("app-1"),
                org.mockito.ArgumentMatchers.eq("POST"),
                org.mockito.ArgumentMatchers.eq("/lab/thread/stop-all"), any());
    }

    @Test
    void unknownInstanceMapsTo404() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
                .thenThrow(new UnknownAppInstanceException("Unknown app instance 'app-9'"));

        mockMvc.perform(get("/chaos/app/app-9/lab/thread/state"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("app-9")));
    }

    @Test
    void offListPathMapsTo404() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
                .thenThrow(new AppPathNotAllowedException(
                        "Path is not in the thread-lab proxy allow-list: GET /lab/toggles"));

        mockMvc.perform(get("/chaos/app/app-1/lab/toggles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("allow-list")));
    }

    @Test
    void unreachableAppMapsTo503() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
                .thenThrow(new AppUnreachableException("app-1 is unreachable at http://app-1:8080"));

        mockMvc.perform(get("/chaos/app/app-1/lab/thread/state"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50301"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unreachable")));
    }

    @Test
    void nonJsonAnswerMapsTo502() throws Exception {
        when(appProxyService.forward(anyString(), anyString(), anyString(), any()))
                .thenThrow(new AppResponseNotJsonException(
                        "app-1 returned a non-JSON response (HTTP 200, content-type text/html)"));

        mockMvc.perform(get("/chaos/app/app-1/lab/thread/state"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("50201"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("non-JSON")));
    }

    @Test
    void unsupportedVerbMapsTo405() throws Exception {
        mockMvc.perform(delete("/chaos/app/app-1/lab/thread/state"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("40501"));

        verify(appProxyService, never()).forward(anyString(), anyString(), anyString(), any());
    }

    @Test
    void oversizedBodyIsRejectedBeforeForwarding() throws Exception {
        when(appProxyService.maxRequestBytes()).thenReturn(4);

        mockMvc.perform(post("/chaos/app/app-1/lab/thread/fault")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("exceeds 4 bytes")));

        verify(appProxyService, never()).forward(anyString(), anyString(), anyString(), any());
    }
}
