package dev.portsync.core.ingest;

/** Which parts of the dashboard an applied event affects; sent to the UI so it refreshes only those. */
public enum Scope {
    BERTHS,
    CRANES,
    YARD,
    GATE,
    CONTAINERS,
    VESSELS
}
