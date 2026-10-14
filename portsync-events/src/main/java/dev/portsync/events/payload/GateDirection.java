package dev.portsync.events.payload;

/** Why a truck visits the terminal. */
public enum GateDirection {
    /** Truck brings an export container into the terminal. */
    DROP_OFF,
    /** Truck collects an import container from the yard. */
    PICK_UP
}
