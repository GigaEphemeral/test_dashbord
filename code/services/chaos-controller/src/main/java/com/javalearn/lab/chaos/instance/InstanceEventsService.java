package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.gateway.ContainerGateway;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage2 B5-04b: read-only OOM / heap-dump event scan over a bounded container
 * log tail. No Prometheus series exists for these events (the plan §T4), so the
 * answer is either a real matched log line or an explicit {@code NO_DATA} -
 * never a synthetic event and never a 0.
 *
 * <p>The scan is bounded twice: Docker reads at most
 * {@code lab.instance.events-log-tail} lines and the answer carries at most
 * {@code lab.instance.events-max-events} events with a per-line character cap.
 * Only a known instance is a 404 (checked before any Docker call).</p>
 */
public class InstanceEventsService {

    public static final String OK = "OK";
    public static final String NO_DATA = "NO_DATA";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    static final String OOM = "OOM";
    static final String HEAP_DUMP = "HEAP_DUMP";
    static final String HEAP_DUMP_PATH = "HEAP_DUMP_PATH";

    private final InstanceConfigProperties properties;
    private final ObjectProvider<ContainerGateway> containerGatewayProvider;

    public InstanceEventsService(InstanceConfigProperties properties,
                                 ObjectProvider<ContainerGateway> containerGatewayProvider) {
        this.properties = properties;
        this.containerGatewayProvider = containerGatewayProvider;
    }

    public InstanceViews.InstanceEventsView events(String instance) {
        if (!properties.isKnownInstance(instance)) {
            throw new com.javalearn.lab.chaos.error.InstanceNotFoundException(
                    "unknown instance '" + instance + "'; configured: " + properties.getNames());
        }
        ContainerGateway gateway = containerGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return new InstanceViews.InstanceEventsView(instance, List.of(),
                    UNAVAILABLE + ": Docker container gateway is not available");
        }
        String container = properties.containerName(instance);
        int tail = Math.max(1, Math.min(1000, properties.getEventsLogTail()));
        String logs;
        try {
            logs = gateway.tailLogs(container, tail);
        } catch (com.javalearn.lab.chaos.error.ContainerNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            return new InstanceViews.InstanceEventsView(instance, List.of(),
                    UNAVAILABLE + ": cannot read logs from " + container + " ("
                            + e.getClass().getSimpleName() + ")");
        }
        List<InstanceViews.InstanceEvent> events = scan(logs);
        return new InstanceViews.InstanceEventsView(instance, events,
                events.isEmpty() ? NO_DATA : OK);
    }

    private List<InstanceViews.InstanceEvent> scan(String logs) {
        List<InstanceViews.InstanceEvent> events = new ArrayList<>();
        if (logs == null || logs.isBlank()) {
            return events;
        }
        int maxEvents = Math.max(1, properties.getEventsMaxEvents());
        int maxChars = Math.max(80, properties.getEventsLineMaxChars());
        for (String raw : logs.split("\\R", -1)) {
            if (events.size() >= maxEvents) {
                break;
            }
            String line = truncate(raw.strip(), maxChars);
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("OutOfMemoryError")) {
                events.add(new InstanceViews.InstanceEvent(OOM, line, "OutOfMemoryError"));
            } else if (line.contains("Heap dump file created")) {
                events.add(new InstanceViews.InstanceEvent(HEAP_DUMP, line,
                        "Heap dump file created"));
            } else if (line.contains("Dumping heap")) {
                events.add(new InstanceViews.InstanceEvent(HEAP_DUMP_PATH, line,
                        heapDumpPath(line)));
            } else if (line.toLowerCase(java.util.Locale.ROOT).contains(".hprof")) {
                // A bare configured heap-dump path also counts; no keyword needed.
                events.add(new InstanceViews.InstanceEvent(HEAP_DUMP_PATH, line, ".hprof"));
            }
        }
        return List.copyOf(events);
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    /** {@code Dumping heap to /path/x.hprof ...} -> {@code /path/x.hprof}. */
    private static String heapDumpPath(String line) {
        String marker = "Dumping heap to ";
        int index = line.indexOf(marker);
        if (index < 0) {
            return "Dumping heap";
        }
        String rest = line.substring(index + marker.length()).strip();
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
            end++;
        }
        return end == 0 ? "Dumping heap" : rest.substring(0, end);
    }
}
