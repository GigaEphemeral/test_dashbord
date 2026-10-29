package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B5-04b OOM/heap-dump scan. A fake log feed proves that only matched lines
 * become events and that an empty window is NO_DATA, never a synthetic event.
 */
class InstanceEventsServiceTest {

    private InstanceConfigProperties properties;
    private ContainerGateway gateway;
    private InstanceEventsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new InstanceConfigProperties();
        properties.validate();
        gateway = mock(ContainerGateway.class);
        ObjectProvider<ContainerGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        service = new InstanceEventsService(properties, provider);
    }

    @Test
    void cleanLogsAnswerNoDataWithNoEvents() {
        when(gateway.tailLogs("jl-app-1", 200))
                .thenReturn("2026-10-29 INFO started\n2026-10-29 INFO healthy\n");

        InstanceViews.InstanceEventsView view = service.events("app-1");

        assertThat(view.instance()).isEqualTo("app-1");
        assertThat(view.events()).isEmpty();
        assertThat(view.note()).isEqualTo(InstanceEventsService.NO_DATA);
    }

    @Test
    void oomAndHeapDumpLinesBecomeEventsWithTheObservedPattern() {
        when(gateway.tailLogs("jl-app-1", 200)).thenReturn(String.join("\n",
                "2026-10-29 INFO request",
                "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space",
                "Dumping heap to /app/lab-snapshots/heapdump.hprof ...",
                "Heap dump file created [1234567 bytes in 0.234 secs]"));

        InstanceViews.InstanceEventsView view = service.events("app-1");

        assertThat(view.note()).isEqualTo(InstanceEventsService.OK);
        assertThat(view.events()).hasSize(3);
        assertThat(view.events().get(0).kind()).isEqualTo("OOM");
        assertThat(view.events().get(0).observed()).isEqualTo("OutOfMemoryError");
        assertThat(view.events().get(1).kind()).isEqualTo("HEAP_DUMP_PATH");
        assertThat(view.events().get(1).observed())
                .isEqualTo("/app/lab-snapshots/heapdump.hprof");
        assertThat(view.events().get(2).kind()).isEqualTo("HEAP_DUMP");
        assertThat(view.events().get(2).line()).contains("Heap dump file created");
    }

    @Test
    void eventsAreBoundedAndLinesAreTruncated() {
        properties.setEventsMaxEvents(2);
        properties.setEventsLineMaxChars(90);
        when(gateway.tailLogs("jl-app-1", 200)).thenReturn(
                "java.lang.OutOfMemoryError: Java heap space " + "x".repeat(300) + "\n"
                        + "java.lang.OutOfMemoryError: Metaspace\n"
                        + "java.lang.OutOfMemoryError: Direct buffer memory\n");

        InstanceViews.InstanceEventsView view = service.events("app-1");

        assertThat(view.events()).hasSize(2);
        assertThat(view.events().get(0).line()).hasSize(90);
        assertThat(view.events().get(1).line()).contains("Metaspace");
    }

    @Test
    void anUnreadableLogIsUnavailableNotNoData() {
        when(gateway.tailLogs(eq("jl-app-1"), anyInt()))
                .thenThrow(new RuntimeException("daemon down"));

        InstanceViews.InstanceEventsView view = service.events("app-1");

        assertThat(view.events()).isEmpty();
        assertThat(view.note()).startsWith(InstanceEventsService.UNAVAILABLE);
    }

    @Test
    void aMissingContainerPropagates404AndAnUnknownInstanceNeverCallsDocker() {
        when(gateway.tailLogs(eq("jl-app-1"), anyInt()))
                .thenThrow(new ContainerNotFoundException("not found"));
        assertThatThrownBy(() -> service.events("app-1"))
                .isInstanceOf(ContainerNotFoundException.class);

        assertThatThrownBy(() -> service.events("app-9"))
                .isInstanceOf(InstanceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noDockerGatewayIsUnavailable() {
        ObjectProvider<ContainerGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        InstanceEventsService noDocker = new InstanceEventsService(properties, provider);

        InstanceViews.InstanceEventsView view = noDocker.events("app-1");

        assertThat(view.events()).isEmpty();
        assertThat(view.note()).startsWith(InstanceEventsService.UNAVAILABLE);
    }
}
