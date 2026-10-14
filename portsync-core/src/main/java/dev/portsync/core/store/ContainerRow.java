package dev.portsync.core.store;

import java.time.Instant;

/** A container known to the terminal. */
public record ContainerRow(String number, String status, String vesselId, Instant lastEventAt) {
}
