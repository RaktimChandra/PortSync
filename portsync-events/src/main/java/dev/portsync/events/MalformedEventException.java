package dev.portsync.events;

/** Thrown when a record cannot be turned into a complete {@link TerminalEvent}. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
