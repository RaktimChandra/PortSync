package dev.portsync.core.api;

import dev.portsync.core.api.dto.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Tag(name = "Terminal", description = "Current terminal state")
public class TerminalController {

    private final TerminalQueryService query;

    public TerminalController(TerminalQueryService query) {
        this.query = query;
    }

    @GetMapping("/overview")
    @Operation(summary = "Headline KPIs: vessels, crane moves/hour, yard utilisation, alerts, truck turn time")
    public Views.Overview overview() {
        return query.overview();
    }

    @GetMapping("/berths")
    @Operation(summary = "Each berth with its vessel and assigned cranes")
    public List<Views.Berth> berths() {
        return query.berths();
    }

    @GetMapping("/cranes")
    @Operation(summary = "All quay cranes with status and moves in the last simulated hour")
    public List<Views.Crane> cranes() {
        return query.cranes();
    }

    @GetMapping("/vessels")
    @Operation(summary = "Vessel visits; filter with ?status=WAITING|AT_BERTH|DEPARTED")
    public List<Views.Vessel> vessels(@RequestParam Optional<String> status) {
        status.ifPresent(s -> {
            if (!List.of("WAITING", "AT_BERTH", "DEPARTED").contains(s.toUpperCase())) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "status must be one of WAITING, AT_BERTH, DEPARTED", java.util.Map.of("status", s));
            }
        });
        return query.vessels(status);
    }

    @GetMapping("/yard/blocks")
    @Operation(summary = "Occupancy of every yard block")
    public List<Views.BlockSummary> blocks() {
        return query.blockSummaries();
    }

    @GetMapping("/yard/blocks/{block}")
    @Operation(summary = "Slot grid of one block (A-D): every stack with its tiers")
    public Views.YardBlock block(@PathVariable String block) {
        return query.block(block);
    }

    @GetMapping("/gate/visits")
    @Operation(summary = "Trucks currently inside the terminal")
    public List<Views.TruckVisit> truckVisits() {
        return query.openTruckVisits();
    }
}
