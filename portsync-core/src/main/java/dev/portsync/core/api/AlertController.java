package dev.portsync.core.api;

import dev.portsync.core.alert.Alert;
import dev.portsync.core.alert.AlertService;
import dev.portsync.core.alert.AlertState;
import dev.portsync.core.api.dto.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/alerts")
@Tag(name = "Alerts", description = "SLA alerts: OPEN -> ACKNOWLEDGED -> RESOLVED")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    @Operation(summary = "Alerts, newest first. Repeat ?state= to filter (OPEN, ACKNOWLEDGED, RESOLVED)")
    public List<Views.Alert> list(@RequestParam(required = false) List<String> state,
                                  @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        List<AlertState> states = new ArrayList<>();
        if (state != null) {
            for (String s : state) {
                try {
                    states.add(AlertState.valueOf(s.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("INVALID_PARAMETER",
                            "state must be OPEN, ACKNOWLEDGED or RESOLVED", Map.of("state", s));
                }
            }
        }
        return alerts.list(states, limit).stream().map(AlertController::toView).toList();
    }

    @PostMapping("/{id}/ack")
    @Operation(summary = "Acknowledge an OPEN alert (409 if it is not OPEN)")
    public Views.Alert acknowledge(@PathVariable long id) {
        return toView(alerts.acknowledge(id));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve an OPEN or ACKNOWLEDGED alert (409 if already resolved). "
            + "If the condition still holds, the next evaluation opens a new alert.")
    public Views.Alert resolve(@PathVariable long id) {
        return toView(alerts.resolve(id));
    }

    static Views.Alert toView(Alert a) {
        return new Views.Alert(a.id(), a.rule(), a.entityId(), a.severity(), a.state().name(), a.message(),
                a.observedValue(), a.threshold(), a.openedAt(), a.lastSeenAt(), a.acknowledgedAt(), a.resolvedAt(),
                a.resolution());
    }
}
