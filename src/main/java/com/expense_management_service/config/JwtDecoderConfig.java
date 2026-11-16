package com.expense_management_service.config;

import com.expense_management_service.security.ResilientJwtDecoder;
import com.expense_management_service.security.UmsJwtDecoderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The single application {@link JwtDecoder}, built from the existing issuer URI. Declaring it here means Spring Boot's own
 * decoder (which re-runs discovery on every failed request) backs off. The issuer URI, the validation rules, and the
 * UMS configuration are unchanged; only the lifecycle of the decoder differs.
 *
 * <p>Nothing is contacted at startup. The first authenticated request builds the decoder, and it is then reused.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        return new ResilientJwtDecoder(() -> UmsJwtDecoderFactory.fromIssuer(issuerUri));
    }
}
