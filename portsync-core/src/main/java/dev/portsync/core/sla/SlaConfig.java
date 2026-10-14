package dev.portsync.core.sla;

import dev.portsync.core.config.PortSyncProperties;
import dev.portsync.core.sla.rules.BerthWaitRule;
import dev.portsync.core.sla.rules.CraneFaultRule;
import dev.portsync.core.sla.rules.CraneProductivityRule;
import dev.portsync.core.sla.rules.TruckTurnTimeRule;
import dev.portsync.core.sla.rules.YardUtilisationRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Builds the SLA rules from the thresholds in application.yml. */
@Configuration
public class SlaConfig {

    @Bean
    public List<SlaRule> slaRules(PortSyncProperties properties) {
        PortSyncProperties.Sla sla = properties.sla();
        return List.of(
                new CraneProductivityRule(sla.craneProductivity().minMovesPerHour(), sla.craneProductivity().window()),
                new CraneFaultRule(sla.craneFault().max()),
                new BerthWaitRule(sla.berthWait().max()),
                new TruckTurnTimeRule(sla.truckTurn().max()),
                new YardUtilisationRule(sla.yardUtilisation().maxRatio(), sla.yardUtilisation().criticalRatio()));
    }
}
