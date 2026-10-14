package dev.portsync.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document served at /v3/api-docs and rendered by Swagger UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI portSyncOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PortSync Core API")
                .version("1.0")
                .description("Terminal-operations monitoring: berths, cranes, yard, containers, SLA alerts, "
                        + "dead letters and a live Server-Sent Events stream. All business times are simulated time.")
                .license(new License().name("MIT")));
    }
}
