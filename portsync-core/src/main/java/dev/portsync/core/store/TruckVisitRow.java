package dev.portsync.core.store;

import java.time.Instant;

/** One truck's trip through the gate. */
public record TruckVisitRow(String id, String containerNumber, String direction, String lane, String truckPlate,
                            String status, Instant gateInAt, Instant gateOutAt) {
}
