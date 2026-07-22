package com.expense_management_service.config;

import com.expense_management_service.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Configures the {@link RestClient} used by {@code UmsClient} to call UMS.
 * <p>
 * Every outgoing request automatically forwards the caller's own
 * {@code Authorization} bearer token — the same one XMS itself already
 * validated — so UMS authorizes the original end user rather than a service
 * account. XMS never mints, caches, or stores a UMS token itself.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final UmsProperties umsProperties;

    @Bean
    public RestClient umsRestClient() {
        return RestClient.builder()
                .baseUrl(umsProperties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    Optional<String> incomingAuth = getIncomingAuthorizationHeader();
                    incomingAuth.ifPresent(auth -> request.getHeaders().set(HttpHeaders.AUTHORIZATION, auth));

                    // TEMPORARY DIAGNOSTIC LOGGING — remove once the UMS 401 is root-caused.
                    log.debug("[UMS-OUT] {} {}", request.getMethod(), request.getURI());
                    log.debug("[UMS-OUT] incoming Authorization header present: {}", incomingAuth.isPresent());
                    incomingAuth.ifPresent(auth ->
                            log.debug("[UMS-OUT] incoming Authorization (first 27 chars): {}...",
                                    auth.substring(0, Math.min(27, auth.length()))));
                    log.debug("[UMS-OUT] outgoing Authorization header set: {}",
                            request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null);

                    return execution.execute(request, body);
                })
                .build();
    }

    private Optional<String> getIncomingAuthorizationHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return Optional.ofNullable(request.getHeader(SecurityConstants.AUTHORIZATION_HEADER));
    }
}
