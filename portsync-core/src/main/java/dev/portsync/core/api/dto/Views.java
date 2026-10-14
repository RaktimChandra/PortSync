package dev.portsync.core.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of the REST API. Plain records serialised by Jackson. */
public final class Views {

    private Views() {
    }

    public record Overview(
            Instant simulatedTime,
            boolean clockSynchronised,
            long vesselsAtBerth,
            long vesselsWaiting,
            long craneMovesLastHour,
            double yardUtilisation,
            List<BlockSummary> blocks,
            long openAlerts,
            long acknowledgedAlerts,
            Double avgTruckTurnMinutes,
            long trucksInTerminal,
            long cranesFaulted,
            long eventsProcessed,
            long dlqEvents) {
    }

    public record BlockSummary(String block, int used, int capacity, double utilisation) {
    }

    public record Crane(String id, String status, String berthId, Instant assignedAt, String faultCode,
                        Instant faultedSince, Instant lastHeartbeatAt, int movesLastHour) {
    }

    public record Vessel(String id, String name, String voyage, String status, String berthId, Instant arrivedAt,
                         Instant berthedAt, Instant departedAt, int dischargePlanned, int discharged,
                         int loadPlanned, int loaded) {
    }

    public record Berth(String id, Vessel vessel, List<Crane> cranes) {
    }

    public record Slot(int tier, String containerNumber) {
    }

    public record Stack(int row, int bay, int height, List<Slot> slots) {
    }

    public record YardBlock(String block, int rows, int bays, int tiers, int capacity, int used, double utilisation,
                            List<Stack> stacks) {
    }

    public record Location(String block, int row, int bay, int tier, String label) {
    }

    public record HistoryItem(UUID eventId, String type, Instant occurredAt, String description) {
    }

    public record Rejection(UUID eventId, String type, String reason, String detail, Instant deadLetteredAt) {
    }

    public record Container(String number, boolean valid, String status, String vesselId, Location location,
                            Instant lastEventAt, List<HistoryItem> history, List<Rejection> rejections) {
    }

    public record ContainerValidation(String input, boolean valid, String reason, Integer expectedCheckDigit) {
    }

    public record DlqItem(Long id, UUID eventId, String type, String entityId, String reason, String detail,
                          String sourceTopic, int sourcePartition, long sourceOffset, Instant deadLetteredAt,
                          String rawValue) {
    }

    public record EventItem(UUID eventId, String type, String entityId, Instant occurredAt, String outcome,
                            String reason, String detail, String topic, Integer partition, Long offset,
                            Instant processedAt) {
    }

    public record Alert(long id, String rule, String entityId, String severity, String state, String message,
                        Double observedValue, Double threshold, Instant openedAt, Instant lastSeenAt,
                        Instant acknowledgedAt, Instant resolvedAt, String resolution) {
    }

    public record TruckVisit(String id, String containerNumber, String direction, String lane, String truckPlate,
                             Instant gateInAt, long minutesInside) {
    }
}
