package dev.portsync.simulator.api;

import dev.portsync.simulator.engine.EventSink;
import dev.portsync.simulator.engine.TerminalSimulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wiring test of the simulator's HTTP API with Kafka replaced by a mock sink. */
@SpringBootTest(properties = {
        "simulator.auto-start=false",
        "spring.kafka.admin.auto-create=false"
})
@AutoConfigureMockMvc
class FaultControllerTest {

    @MockitoBean
    EventSink sink;

    @Autowired
    MockMvc mvc;

    @Autowired
    TerminalSimulation simulation;

    @Test
    @DisplayName("injections before the simulation started answer 409, then work once it runs")
    void injectionLifecycle() throws Exception {
        mvc.perform(post("/api/faults/invalid-container")).andExpect(status().isConflict());

        Instant start = Instant.parse("2026-10-14T06:00:00Z");
        simulation.start(start);
        simulation.tick(start.plusSeconds(600));

        mvc.perform(post("/api/faults/invalid-container"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedOutcome").value("REJECTED: INVALID_CHECK_DIGIT"))
                .andExpect(jsonPath("$.events.length()").value(1));
        mvc.perform(post("/api/faults/cranes/qc02/fault").param("code", "TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedOutcome").value("APPLIED"));
        mvc.perform(post("/api/faults/cranes/QC99/fault")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/faults/impossible").param("kind", "GATE_OUT_NOT_IN_YARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedOutcome").value("REJECTED: CONTAINER_NOT_IN_YARD"));
        mvc.perform(post("/api/faults/duplicates").param("count", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/simulation/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminal.cranes.QC02").value("FAULTED TEST"));
    }
}
