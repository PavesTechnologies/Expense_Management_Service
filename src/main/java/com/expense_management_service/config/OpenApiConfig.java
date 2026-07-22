package com.expense_management_service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a single {@code bearerAuth} HTTP security scheme so Swagger UI
 * sends the caller's UMS-issued JWT on "Try it out" requests. XMS does not
 * issue tokens itself, so no login/token-generation UI is exposed here.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI xmsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Expense Management Service (XMS)")
                        .description("Expense management module of the ERP suite. Authentication and identity "
                                + "are owned by UMS; XMS only validates the JWT UMS issues.")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
