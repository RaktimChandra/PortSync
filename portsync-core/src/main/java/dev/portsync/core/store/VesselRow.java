package dev.portsync.core.store;

import java.time.Instant;

/** A vessel visit. */
public record VesselRow(String id, String name, String voyage, String status, String berthId, Instant arrivedAt,
                        Instant berthedAt, Instant departedAt, int dischargePlanned, int loadPlanned,
                        int discharged, int loaded) {
}
