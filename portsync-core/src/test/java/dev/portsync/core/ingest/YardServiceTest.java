package dev.portsync.core.ingest;

import dev.portsync.core.ingest.handler.YardService;
import dev.portsync.core.store.ContainerHistoryRepository;
import dev.portsync.core.store.YardRepository;
import dev.portsync.events.yard.SlotRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YardServiceTest {

    private static final Instant T = Instant.parse("2026-10-14T08:00:00Z");
    private static final SlotRef.StackRef STACK = new SlotRef.StackRef("A", 1, 1);

    @Mock
    YardRepository repository;
    @Mock
    ContainerHistoryRepository history;

    @Test
    @DisplayName("placing on top of a stack writes exactly that slot")
    void placeOnTop() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("NEWU0000001")).thenReturn(Optional.empty());
        when(repository.lockStack(STACK)).thenReturn(new String[]{"AAAU0000001", null, null, null});

        YardService.Placement placement = yard.place("NEWU0000001", STACK.tier(2), T, null);

        verify(repository).setOccupant(STACK.tier(2), "NEWU0000001", T);
        assertThat(placement.adjusted()).isFalse();
    }

    @Test
    @DisplayName("a reported tier above an empty tier lands on top instead, so nothing can float")
    void reportedTierTooHighLandsOnTop() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("NEWU0000001")).thenReturn(Optional.empty());
        when(repository.lockStack(STACK)).thenReturn(new String[4]);

        YardService.Placement placement = yard.place("NEWU0000001", STACK.tier(3), T, null);

        verify(repository).setOccupant(STACK.tier(1), "NEWU0000001", T);
        assertThat(placement.slot()).isEqualTo(STACK.tier(1));
        assertThat(placement.adjusted()).isTrue();
        assertThat(placement.reportedTier()).isEqualTo(3);
    }

    @Test
    @DisplayName("a reported tier that is already occupied lands on top, it never overwrites a box")
    void occupiedReportedTierLandsOnTop() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("NEWU0000001")).thenReturn(Optional.empty());
        when(repository.lockStack(STACK)).thenReturn(new String[]{"AAAU0000001", null, null, null});

        YardService.Placement placement = yard.place("NEWU0000001", STACK.tier(1), T, null);

        verify(repository).setOccupant(STACK.tier(2), "NEWU0000001", T);
        verify(repository, never()).setOccupant(eq(STACK.tier(1)), anyString(), any());
        assertThat(placement.adjusted()).isTrue();
    }

    @Test
    @DisplayName("a full stack is rejected as STACK_FULL and nothing is written")
    void fullStackRejected() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("NEWU0000001")).thenReturn(Optional.empty());
        when(repository.lockStack(STACK)).thenReturn(new String[]{"A1", "A2", "A3", "A4"});

        assertThatThrownBy(() -> yard.place("NEWU0000001", STACK.tier(4), T, null))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.STACK_FULL));
        verify(repository, never()).setOccupant(any(), anyString(), any());
    }

    @Test
    @DisplayName("moving a box to the top of its own full stack is allowed (it is lifted first)")
    void restackWithinFullStack() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("A1")).thenReturn(Optional.of(STACK.tier(1)));
        when(repository.lockStack(STACK)).thenReturn(new String[]{"A1", "A2", "A3", "A4"});

        YardService.Placement placement = yard.place("A1", STACK.tier(4), T, null);

        assertThat(placement.slot()).isEqualTo(STACK.tier(4));
        assertThat(placement.from()).contains(STACK.tier(1));
        verify(repository).setOccupant(STACK.tier(1), "A2", T);
        verify(repository).setOccupant(STACK.tier(4), "A1", T);
    }

    @Test
    @DisplayName("removal clears changed slots BEFORE refilling them (the unique index is per statement)")
    void removalSettlesInSafeOrder() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("BOTU0000001")).thenReturn(Optional.of(STACK.tier(1)));
        when(repository.lockStack(STACK)).thenReturn(new String[]{"BOTU0000001", "MIDU0000002", "TOPU0000003", null});

        SlotRef from = yard.remove("BOTU0000001", T, null);

        assertThat(from).isEqualTo(STACK.tier(1));
        InOrder order = inOrder(repository);
        order.verify(repository).setOccupant(STACK.tier(1), null, T);
        order.verify(repository).setOccupant(STACK.tier(2), null, T);
        order.verify(repository).setOccupant(STACK.tier(3), null, T);
        order.verify(repository).setOccupant(STACK.tier(1), "MIDU0000002", T);
        order.verify(repository).setOccupant(STACK.tier(2), "TOPU0000003", T);
        verify(history).add(eq("MIDU0000002"), any(), eq("REHANDLE"), eq(T), anyString());
        verify(history).add(eq("TOPU0000003"), any(), eq("REHANDLE"), eq(T), anyString());
    }

    @Test
    void removingAContainerNotInTheYardIsRejected() {
        YardService yard = new YardService(repository, history);
        when(repository.locate("GONU0000001")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> yard.remove("GONU0000001", T, null))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.CONTAINER_NOT_IN_YARD));
    }

    @Test
    void outsideLayoutIsRejected() {
        YardService yard = new YardService(repository, history);
        assertThatThrownBy(() -> yard.place("NEWU0000001", new SlotRef("Z", 1, 1, 1), T, null))
                .isInstanceOfSatisfying(RejectedEventException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.SLOT_INVALID));
    }
}
