package dev.portsync.simulator.engine;

import java.time.Instant;

/** Mutable state of one quay crane inside the simulation. */
final class QuayCrane {

    final String id;
    String berthId;
    boolean faulted;
    String faultCode;
    Instant nextMoveAt;
    Instant nextHeartbeatAt;
    /** 1.0 = normal productivity; fault injection can slow a crane down. */
    double speed = 1.0;

    QuayCrane(String id) {
        this.id = id;
    }
}
