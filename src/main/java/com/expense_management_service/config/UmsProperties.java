package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code ums.*} configuration namespace.
 * <p>
 * {@code jwksUri} feeds {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
 * (kept as its own property so the JWKS path doesn't need to be re-derived from
 * {@code baseUrl} elsewhere); {@code baseUrl} is the root {@code UmsClient} uses to
 * build every UMS REST call.
 *
 * @param baseUrl root URL of the UMS service, e.g. {@code http://localhost:8000/ums}
 * @param jwksUri full JWKS endpoint URL, e.g. {@code http://localhost:8000/ums/.well-known/jwks.json}
 */
@ConfigurationProperties(prefix = "ums")
public record UmsProperties(String baseUrl, String jwksUri) {
}
