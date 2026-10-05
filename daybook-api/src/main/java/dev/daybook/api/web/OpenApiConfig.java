package dev.daybook.api.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata (FR-12.4). springdoc derives endpoints from the controllers, but cannot see how
 * our custom security filter authenticates, so the bearer scheme is declared here. Without it,
 * Swagger UI shows no "Authorize" button and every protected call fails with 401.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  private static final String SCHEME = "apiKey";

  @Bean
  OpenAPI daybookOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Daybook API")
                .version("v1")
                .description(
                    "Wallet service backed by a double-entry ledger. Amounts are integers in"
                        + " minor units (paise). Mutating endpoints require an Idempotency-Key"
                        + " header; errors are RFC 9457 problem documents."))
        .components(
            new Components()
                .addSecuritySchemes(
                    SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description(
                            "Tenant key (dbk_...) for /v1/**, or the admin key for"
                                + " /v1/admin/**. Paste the key only; Swagger adds 'Bearer '.")))
        .addSecurityItem(new SecurityRequirement().addList(SCHEME));
  }
}
