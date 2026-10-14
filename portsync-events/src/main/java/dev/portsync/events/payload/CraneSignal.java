package dev.portsync.events.payload;

/** What a CRANE_STATUS event reports. */
public enum CraneSignal {
    /** Periodic "I am alive" signal; also advances the consumer's view of simulated time. */
    HEARTBEAT,
    /** The crane stopped working. */
    FAULT,
    /** The crane is working again. */
    RECOVERED
}
