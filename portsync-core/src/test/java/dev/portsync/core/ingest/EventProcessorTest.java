package dev.portsync.core.ingest;

import dev.portsync.core.store.EntityVersionRepository;
import dev.portsync.core.store.ProcessedEventRepository;
import dev.portsync.events.EntityKind;
import dev.portsync.events.EventJson;
import dev.portsync.events.EventType;
import dev.portsync.events.TerminalEvent;
import dev.portsync.events.payload.CraneSignal;
import dev.portsync.events.payload.CraneStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The processing pipeline's decisions, with every collaborator mocked. */
@ExtendWith(MockitoExtension.class)
class EventProcessorTest {

    private static final Instant T = Instant.parse("2026-10-14T08:00:00Z");

    @Mock
    ProcessedEventRepository processed;
    @Mock
    EntityVersionRepository versions;
    @Mock
    EventRouter router;
    @Mock
    DeadLetterPublisher deadLetters;
    @Mock
    ApplicationEventPublisher publisher;
    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    EventProcessor processor;

    private TerminalEvent event;
    private RecordMeta meta;

    @BeforeEach
    void setUp() {
        event = TerminalEvent.of(EventType.CRANE_STATUS, T, "QC01", "test", new CraneStatus(CraneSignal.FAULT, "HOIST"));
        meta = new RecordMeta("terminal.crane", 1, 42L, "QC01", EventJson.write(event), 0L);
    }

    @Test
    @DisplayName("a duplicate eventId changes nothing and is not dead-lettered")
    void duplicateIsSkipped() {
        when(processed.claim(event, meta)).thenReturn(false);

        ProcessingResult result = processor.process(event, meta);

        assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
        verifyNoInteractions(router, versions, deadLetters);
        verify(processed, never()).finish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an event older than the entity's last applied event is recorded as IGNORED_STALE")
    void staleEventIsIgnored() {
        when(processed.claim(event, meta)).thenReturn(true);
        when(versions.lastOccurredAt(EntityKind.CRANE, "QC01")).thenReturn(Optional.of(T.plusSeconds(60)));

        ProcessingResult result = processor.process(event, meta);

        assertThat(result.outcome()).isEqualTo(Outcome.IGNORED_STALE);
        verify(processed).finish(eq(event.eventId()), eq(Outcome.IGNORED_STALE), eq("STALE"), anyString());
        verifyNoInteractions(router, deadLetters);
        verify(versions, never()).advance(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an event with the same timestamp as the last applied one is still applied")
    void equalTimestampIsApplied() {
        when(processed.claim(event, meta)).thenReturn(true);
        when(versions.lastOccurredAt(EntityKind.CRANE, "QC01")).thenReturn(Optional.of(T));
        when(router.apply(event)).thenReturn(EnumSet.of(Scope.CRANES));

        assertThat(processor.process(event, meta).outcome()).isEqualTo(Outcome.APPLIED);
        verify(versions).advance(EntityKind.CRANE, "QC01", T, event.eventId());
    }

    @Test
    @DisplayName("a valid event is applied, the entity version advances and listeners are notified")
    void validEventIsApplied() {
        when(processed.claim(event, meta)).thenReturn(true);
        when(versions.lastOccurredAt(EntityKind.CRANE, "QC01")).thenReturn(Optional.empty());
        when(router.apply(event)).thenReturn(EnumSet.of(Scope.CRANES, Scope.BERTHS));

        ProcessingResult result = processor.process(event, meta);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(result.scopes()).containsExactlyInAnyOrder(Scope.CRANES, Scope.BERTHS);
        verify(versions).advance(EntityKind.CRANE, "QC01", T, event.eventId());
        verify(publisher).publishEvent(result);
        verifyNoInteractions(deadLetters);
    }

    @Test
    @DisplayName("a rejected event is recorded, dead-lettered with its reason, and does not advance the version")
    void rejectionGoesToDlq() {
        when(processed.claim(event, meta)).thenReturn(true);
        when(versions.lastOccurredAt(EntityKind.CRANE, "QC01")).thenReturn(Optional.empty());
        when(router.apply(event)).thenThrow(new RejectedEventException(RejectReason.CRANE_FAULTED, "QC01 is faulted"));

        ProcessingResult result = processor.process(event, meta);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.reason()).isEqualTo("CRANE_FAULTED");
        verify(processed).finish(event.eventId(), Outcome.REJECTED, "CRANE_FAULTED", "QC01 is faulted");
        verify(deadLetters).publish(meta, RejectReason.CRANE_FAULTED, "QC01 is faulted");
        verify(versions, never()).advance(any(), any(), any(), any());
    }

    @Test
    @DisplayName("if the DLQ send fails the exception propagates so the transaction rolls back and Kafka retries")
    void dlqFailurePropagates() {
        when(processed.claim(event, meta)).thenReturn(true);
        when(versions.lastOccurredAt(EntityKind.CRANE, "QC01")).thenReturn(Optional.empty());
        when(router.apply(event)).thenThrow(new RejectedEventException(RejectReason.CRANE_FAULTED, "x"));
        doThrow(new IllegalStateException("broker down")).when(deadLetters).publish(any(), any(), any());

        assertThatThrownBy(() -> processor.process(event, meta)).hasMessageContaining("broker down");
    }

    @Test
    @DisplayName("unexpected errors are not swallowed: they propagate to the Kafka error handler for retry")
    void unexpectedErrorPropagates() {
        when(processed.claim(event, meta)).thenReturn(true);
        when(versions.lastOccurredAt(EntityKind.CRANE, "QC01")).thenReturn(Optional.empty());
        when(router.apply(event)).thenThrow(new IllegalStateException("db connection lost"));

        assertThatThrownBy(() -> processor.process(event, meta)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(deadLetters);
    }
}
