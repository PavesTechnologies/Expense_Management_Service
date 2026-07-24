package com.expense_management_service.config;

import com.expense_management_service.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.http.HttpClient;
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
    private final EmployeeOnboardingProperties employeeOnboardingProperties;

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

    /**
     * RestClient for Employee Onboarding, which owns Department master data (see {@code DepartmentClient}).
     * Forwards the caller's own bearer token, same as {@link #umsRestClient()}.
     * <p>
     * <b>Confirmed root cause (captured via temporary diagnostics, now removed):</b>
     * {@code GET /ems/masters/departments} responds {@code 307 Temporary Redirect} with
     * {@code Location: http://ec2-13-207-112-154.ap-south-1.compute.amazonaws.com/ems/masters/departments/}
     * and an empty body. With no explicit {@code requestFactory}, {@code RestClient.builder()}
     * fell back to the JDK's {@link JdkClientHttpRequestFactory}, whose default
     * {@link HttpClient} does not follow redirects — so XMS received the raw, empty-bodied
     * 307 itself. Spring's default error handler never flags 3xx as an error, so no
     * exception was thrown either; {@code .body(...)} on that empty response returned
     * {@code null}. Postman/browsers follow redirects transparently by default, which is
     * why the same call "worked" there.
     * <p>
     * The fix below builds the {@link HttpClient} explicitly with
     * {@link HttpClient.Redirect#ALWAYS} rather than {@code NORMAL}: the captured
     * {@code Location} redirects from an HTTPS URL to a plain-HTTP URL, and the JDK's
     * {@code NORMAL} policy is documented to follow every redirect <em>except</em> an
     * HTTPS-to-HTTP downgrade (a deliberate protocol-downgrade guard) — so {@code NORMAL}
     * would reproduce this exact same null-body symptom against this specific endpoint.
     */
    @Bean
    public RestClient employeeOnboardingRestClient() {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        return RestClient.builder()
                .baseUrl(employeeOnboardingProperties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(jdkHttpClient))
                .requestInterceptor((request, body, execution) -> {
                    Optional<String> incomingAuth = getIncomingAuthorizationHeader();
                    incomingAuth.ifPresent(auth -> request.getHeaders().set(HttpHeaders.AUTHORIZATION, auth));
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
