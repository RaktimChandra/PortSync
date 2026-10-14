package dev.portsync.events;

import java.util.List;

/** Kafka topic names. Kept in one place so producer and consumer cannot drift apart. */
public final class Topics {

    public static final String GATE = "terminal.gate";
    public static final String YARD = "terminal.yard";
    public static final String BERTH = "terminal.berth";
    public static final String CRANE = "terminal.crane";
    public static final String DLQ = "terminal.dlq";

    /** Topics carrying operational events (everything except the dead-letter topic). */
    public static final List<String> OPERATIONAL = List.of(GATE, YARD, BERTH, CRANE);

    /** Every topic the system uses. */
    public static final List<String> ALL = List.of(GATE, YARD, BERTH, CRANE, DLQ);

    /** Partitions per topic. Three partitions let the core service consume with three threads. */
    public static final int PARTITIONS = 3;

    /** Header names written on dead-lettered records. */
    public static final String HEADER_DLQ_REASON = "portsync-dlq-reason";
    public static final String HEADER_DLQ_DETAIL = "portsync-dlq-detail";
    public static final String HEADER_SOURCE_TOPIC = "portsync-source-topic";
    public static final String HEADER_SOURCE_PARTITION = "portsync-source-partition";
    public static final String HEADER_SOURCE_OFFSET = "portsync-source-offset";

    private Topics() {
    }
}
