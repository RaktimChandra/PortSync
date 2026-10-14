package dev.portsync.simulator.engine;

import java.time.Instant;

/** Mutable state of one vessel visit inside the simulation. */
final class VesselVisit {

    final String id;
    final String name;
    final String voyage;
    final Instant arrivedAt;
    final int dischargePlanned;
    final int loadPlanned;
    String berthId;
    int discharged;
    int loaded;
    boolean departureScheduled;

    VesselVisit(String id, String name, String voyage, Instant arrivedAt, int dischargePlanned, int loadPlanned) {
        this.id = id;
        this.name = name;
        this.voyage = voyage;
        this.arrivedAt = arrivedAt;
        this.dischargePlanned = dischargePlanned;
        this.loadPlanned = loadPlanned;
    }

    boolean dischargeDone() {
        return discharged >= dischargePlanned;
    }

    boolean loadDone() {
        return loaded >= loadPlanned;
    }
}
