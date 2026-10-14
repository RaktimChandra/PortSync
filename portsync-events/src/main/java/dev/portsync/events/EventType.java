package dev.portsync.events;

/**
 * Every event the terminal emits, with the topic it travels on and the entity it is keyed by.
 *
 * <table>
 *   <caption>Routing</caption>
 *   <tr><th>Type</th><th>Topic</th><th>entityId / partition key</th></tr>
 *   <tr><td>VESSEL_ARRIVED, BERTH_ASSIGNED, VESSEL_DEPARTED</td><td>terminal.berth</td><td>vessel visit id</td></tr>
 *   <tr><td>CRANE_STATUS</td><td>terminal.crane</td><td>crane id</td></tr>
 *   <tr><td>CRANE_MOVE</td><td>terminal.crane</td><td>container number</td></tr>
 *   <tr><td>GATE_IN, GATE_OUT</td><td>terminal.gate</td><td>container number</td></tr>
 *   <tr><td>YARD_MOVE</td><td>terminal.yard</td><td>container number</td></tr>
 * </table>
 */
public enum EventType {
    VESSEL_ARRIVED(Topics.BERTH, EntityKind.VESSEL),
    BERTH_ASSIGNED(Topics.BERTH, EntityKind.VESSEL),
    VESSEL_DEPARTED(Topics.BERTH, EntityKind.VESSEL),
    CRANE_MOVE(Topics.CRANE, EntityKind.CONTAINER),
    CRANE_STATUS(Topics.CRANE, EntityKind.CRANE),
    GATE_IN(Topics.GATE, EntityKind.CONTAINER),
    GATE_OUT(Topics.GATE, EntityKind.CONTAINER),
    YARD_MOVE(Topics.YARD, EntityKind.CONTAINER);

    private final String topic;
    private final EntityKind entityKind;

    EventType(String topic, EntityKind entityKind) {
        this.topic = topic;
        this.entityKind = entityKind;
    }

    public String topic() {
        return topic;
    }

    public EntityKind entityKind() {
        return entityKind;
    }
}
