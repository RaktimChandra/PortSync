package dev.portsync.core.alert;

/** OPEN -> ACKNOWLEDGED -> RESOLVED, or OPEN -> RESOLVED. RESOLVED is terminal. */
public enum AlertState {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED;

    public boolean isActive() {
        return this != RESOLVED;
    }
}
