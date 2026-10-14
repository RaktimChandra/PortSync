package dev.portsync.core.ingest;

import dev.portsync.core.clock.SimulatedClock;
import dev.portsync.core.config.PortSyncProperties;
import dev.portsync.core.ops.ProcessingStats;
import dev.portsync.events.EventJson;
import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.Topics;
import dev.portsync.events.payload.CraneMove;
import dev.portsync.events.payload.MoveKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The listener's grace period for cross-topic ordering, and its handling of unreadable records. */
@ExtendWith(MockitoExtension.class)
class TerminalEventListenerTest {

    private static final Instant T = Instant.parse("2026-10-14T08:00:00Z");
    private static final int GRACE_RETRIES = 3;

    @Mock
    EventProcessor processor;
    @Mock
    DeadLetterPublisher deadLetters;

    private SimpleMeterRegistry registry;
    private TerminalEventListener listener;
    private TerminalEvent event;
    private ConsumerRecord<String, String> record;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        PortSyncProperties properties = new PortSyncProperties(null, null,
                new PortSyncProperties.Consumer("test", 3, 5, GRACE_RETRIES, Duration.ofMillis(1)), null);
        listener = new TerminalEventListener(processor, deadLetters,
                new SimulatedClock(Clock.systemUTC(), 60, Duration.ofMinutes(10)), new ProcessingStats(), registry, properties);
        event = TerminalEvent.of(EventType.CRANE_MOVE, T, "CSQU3054383", "test", new CraneMove("QC01", "V-0001", MoveKind.DISCHARGE));
        record = new ConsumerRecord<>(Topics.CRANE, 0, 7L, event.partitionKey(), EventJson.write(event));
    }

    private static final RecordMeta META = new RecordMeta(Topics.CRANE, 0, 7L, "k", "{}", 0L);

    private static ProcessingResult applied(TerminalEvent event) {
        return ProcessingResult.of(event, META, Outcome.APPLIED, null, null, Set.of());
    }

    private static DeferredEventException deferred() {
        return new DeferredEventException(new RejectedEventException(RejectReason.VESSEL_STATE_CONFLICT, "not berthed yet"));
    }

    @Test
    @DisplayName("an event that is not applicable yet is retried until the other topic catches up, then applied")
    void deferredEventIsRetriedUntilApplied() {
        when(processor.process(eq(event), any(), anyBoolean()))
                .thenThrow(deferred())
                .thenThrow(deferred())
                .thenReturn(applied(event));

        listener.handle(record);

        verify(processor, times(3)).process(eq(event), any(), eq(false));
        verify(processor, never()).process(eq(event), any(), eq(true));
        assertThat(registry.get("portsync.events.deferred").tag("reason", "VESSEL_STATE_CONFLICT").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("after the grace retries the last attempt is final, so the processor dead-letters the event")
    void graceRetriesEndWithAFinalAttempt() {
        when(processor.process(eq(event), any(), eq(false))).thenThrow(deferred());
        when(processor.process(eq(event), any(), eq(true)))
                .thenReturn(ProcessingResult.of(event, META, Outcome.REJECTED, "VESSEL_STATE_CONFLICT", "x", Set.of()));

        listener.handle(record);

        var order = inOrder(processor);
        order.verify(processor, times(GRACE_RETRIES)).process(eq(event), any(), eq(false));
        order.verify(processor).process(eq(event), any(), eq(true));
    }

    @Test
    @DisplayName("a record that is not a valid event goes straight to the DLQ as MALFORMED_EVENT")
    void malformedRecordIsDeadLetteredWithoutProcessing() {
        listener.handle(new ConsumerRecord<>(Topics.GATE, 1, 3L, "k", "{not json"));

        verify(deadLetters).publish(any(), eq(RejectReason.MALFORMED_EVENT), any());
        verifyNoInteractions(processor);
        assertThat(registry.get("portsync.dlq.events").tag("reason", "MALFORMED_EVENT").counter().count()).isEqualTo(1.0);
    }
}
