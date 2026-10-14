package dev.portsync.simulator.api;

import dev.portsync.simulator.engine.TerminalSimulation;
import dev.portsync.simulator.engine.TerminalSimulation.ImpossibleKind;
import dev.portsync.simulator.engine.TerminalSimulation.Injection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fault injection for demos and tests. Every endpoint returns what was published and what the core
 * service is expected to do with it, so the effect can be checked on the dashboard or the DLQ page.
 */
@RestController
@Validated
@RequestMapping("/api/faults")
@Tag(name = "Fault injection", description = "Make things go wrong on purpose")
public class FaultController {

    private final TerminalSimulation simulation;

    public FaultController(TerminalSimulation simulation) {
        this.simulation = simulation;
    }

    @PostMapping("/cranes/{craneId}/fault")
    @Operation(summary = "Fault a quay crane until it is recovered")
    public Injection faultCrane(@PathVariable String craneId, @RequestParam(required = false) String code) {
        return simulation.faultCrane(craneId.toUpperCase(), code);
    }

    @PostMapping("/cranes/{craneId}/recover")
    @Operation(summary = "Recover a faulted crane")
    public Injection recoverCrane(@PathVariable String craneId) {
        return simulation.recoverCrane(craneId.toUpperCase());
    }

    @PostMapping("/cranes/{craneId}/slow")
    @Operation(summary = "Run a crane at a fraction of its normal speed (factor=1 restores it)")
    public Injection slowCrane(@PathVariable String craneId, @RequestParam(defaultValue = "0.5") double factor) {
        return simulation.slowCrane(craneId.toUpperCase(), factor);
    }

    @PostMapping("/gate-surge")
    @Operation(summary = "Burst of export trucks into one block (default: the fullest, enough to pass ~90%)")
    public Injection gateSurge(@RequestParam(required = false) @Min(1) @Max(200) Integer trucks,
                               @RequestParam(required = false) String block) {
        return simulation.gateSurge(trucks, block);
    }

    @PostMapping("/vessel-surge")
    @Operation(summary = "Several vessels arrive at once so some must wait for a berth")
    public Injection vesselSurge(@RequestParam(defaultValue = "4") @Min(1) @Max(10) int vessels) {
        return simulation.vesselSurge(vessels);
    }

    @PostMapping("/duplicates")
    @Operation(summary = "Re-send the most recent events with their original eventIds")
    public Injection duplicates(@RequestParam(defaultValue = "5") @Min(1) @Max(200) int count) {
        return simulation.duplicates(count);
    }

    @PostMapping("/out-of-order")
    @Operation(summary = "Send a back-dated event that is older than the entity's latest event")
    public Injection outOfOrder() {
        return simulation.outOfOrder();
    }

    @PostMapping("/invalid-container")
    @Operation(summary = "Gate-in of a container number with a wrong ISO 6346 check digit")
    public Injection invalidContainer() {
        return simulation.invalidContainer();
    }

    @PostMapping("/impossible")
    @Operation(summary = "Send an impossible event: GATE_OUT_NOT_IN_YARD, MOVE_ON_FAULTED_CRANE, OUTSIDE_YARD or MALFORMED_JSON")
    public Injection impossible(@RequestParam ImpossibleKind kind) {
        return simulation.impossible(kind);
    }

    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidParameter(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
