package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.schedule.FaultScheduleProperties;
import com.javalearn.lab.chaos.schedule.FaultScheduleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3-00b create-time persistence: the ledger stores the validator's canonical
 * entries, not the raw request map (manual holds become recovery=manual/ttl=null
 * and actions are lowercased). No Docker, no database.
 */
class RunLedgerServiceFaultScheduleTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:15:30.123Z");

    private LedgerWriter writer;
    private RunLedgerService service;

    @BeforeEach
    void setUp() {
        LedgerProperties properties = new LedgerProperties();
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "3", null, NOW));
        RunLedgerRepository repository = mock(RunLedgerRepository.class);
        when(repository.maxRunSequence(anyString())).thenReturn(0);
        writer = mock(LedgerWriter.class);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new RunLedgerService(properties, mock(LedgerTargetResolver.class), bootstrap,
                repository, writer, mock(LedgerStash.class),
                new FaultScheduleValidator(properties, new ChaosProperties(),
                        new FaultScheduleProperties()),
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsTheNormalisedTimelineInsteadOfTheRawRequest() throws Exception {
        Map<String, Object> raw = new HashMap<>();
        raw.put("atSeconds", 10);
        raw.put("target", "jl-app-2");
        raw.put("action", "FREEZE");
        raw.put("recovery", "auto");
        raw.put("ttlSeconds", -1);

        service.create(new RunCreateRequest("load", Map.of(), null, null, null, List.of(raw)));

        ArgumentCaptor<Map<String, String>> groups = ArgumentCaptor.forClass(Map.class);
        verify(writer).persistCreate(any(RunRecord.class), groups.capture());
        JsonNode persisted = new ObjectMapper().readTree(groups.getValue().get("faultSchedule"));
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).path("action").asText()).isEqualTo("freeze");
        assertThat(persisted.get(0).path("recovery").asText()).isEqualTo("manual");
        assertThat(persisted.get(0).has("ttlSeconds")).isTrue();
        assertThat(persisted.get(0).path("ttlSeconds").isNull()).isTrue();
    }

    @Test
    void anInvalidTimelineIsRejectedBeforeAnythingIsPersisted() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("atSeconds", 10);
        raw.put("target", "jl-app-1");
        raw.put("action", "kill");
        raw.put("recovery", "auto");
        raw.put("ttlSeconds", 30);
        raw.put("unexpected", true);

        assertThatThrownBy(() -> service.create(new RunCreateRequest("load", Map.of(), null,
                null, null, List.of(raw))))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("unknown key");

        verify(writer, never()).persistCreate(any(RunRecord.class),
                org.mockito.ArgumentMatchers.<Map<String, String>>any());
    }
}
