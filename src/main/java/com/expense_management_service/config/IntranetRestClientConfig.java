package com.expense_management_service.config;

import com.expense_management_service.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * RestClients for Leave Management (LMS) and Timesheets (TMS), used only by the Paves Assistant to read the caller's
 * own data. Like the PMS/RMS clients, each forwards the caller's own bearer token - no service account - so these
 * services apply their usual per-user access rules. Redirects are followed (the dev gateway redirects some paths).
 */
@Configuration
@RequiredArgsConstructor
public class IntranetRestClientConfig {

    private final IntranetServicesProperties properties;

    @Bean
    public RestClient lmsRestClient() {
        return forwardingClient(properties.lmsBaseUrl());
    }

    @Bean
    public RestClient tmsRestClient() {
        return forwardingClient(properties.tmsBaseUrl());
    }

    /** AI Screening (AIRS). Blank until its address is configured; IntranetClient checks before calling. */
    @Bean
    public RestClient airsRestClient() {
        return forwardingClient(properties.airsBaseUrl());
    }

    /** Accounts Payable. Blank until its address is configured. */
    @Bean
    public RestClient apRestClient() {
        return forwardingClient(properties.apBaseUrl());
    }

    /** Account Receivable. Blank until its address is configured. */
    @Bean
    public RestClient arRestClient() {
        return forwardingClient(properties.arBaseUrl());
    }

    private static RestClient forwardingClient(String baseUrl) {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkHttpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));
        RestClient.Builder builder = RestClient.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    incomingAuthorization().ifPresent(auth -> request.getHeaders().set(HttpHeaders.AUTHORIZATION, auth));
                    return execution.execute(request, body);
                })
                .build();
    }

    private static Optional<String> incomingAuthorization() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = attributes.getRequest();
        return Optional.ofNullable(request.getHeader(SecurityConstants.AUTHORIZATION_HEADER));
    }
}
