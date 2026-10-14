package dev.portsync.simulator.engine;

import dev.portsync.events.TerminalEvent;

/** Where the simulation sends events. Kafka in production, an in-memory list in tests. */
public interface EventSink {

    void publish(TerminalEvent event);

    /** Publishes an arbitrary raw value (used only to inject malformed records). */
    void publishRaw(String topic, String key, String value);
}
