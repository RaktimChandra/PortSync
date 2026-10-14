package dev.portsync.core.api;

import dev.portsync.core.api.dto.Views;
import dev.portsync.events.ContainerNumber;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containers")
@Tag(name = "Containers", description = "ISO 6346 validation and container lookup")
public class ContainerController {

    private final TerminalQueryService query;

    public ContainerController(TerminalQueryService query) {
        this.query = query;
    }

    @GetMapping("/{number}")
    @Operation(summary = "Validate the check digit, then return location, status and full event history",
            description = "400 INVALID_CONTAINER_NUMBER if the number is malformed or the check digit is wrong "
                    + "(details.expectedCheckDigit says what it should be); 404 CONTAINER_NOT_FOUND if valid but never seen.")
    public Views.Container container(@PathVariable String number) {
        return query.container(number);
    }

    @GetMapping("/{number}/validation")
    @Operation(summary = "Only validate the number (always 200)")
    public Views.ContainerValidation validate(@PathVariable String number) {
        ContainerNumber.Validation v = ContainerNumber.validate(number);
        return new Views.ContainerValidation(v.input(), v.valid(), v.reason(), v.expectedCheckDigit());
    }
}
