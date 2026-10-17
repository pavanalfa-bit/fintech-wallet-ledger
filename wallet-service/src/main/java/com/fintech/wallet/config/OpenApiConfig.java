package com.fintech.wallet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fintech Wallet & Double-Entry Ledger API")
                        .version("1.0.0")
                        .description("High-throughput enterprise fintech service featuring strict double-entry ledger bookkeeping, " +
                                "deterministic concurrency control, idempotency key guarantees, and transactional outbox event streaming to Kafka.")
                        .contact(new Contact().name("Fintech Engineering Team").email("engineering@fintech.dev"))
                        .license(new License().name("Apache 2.0").url("https://spring.io")));
    }
}
