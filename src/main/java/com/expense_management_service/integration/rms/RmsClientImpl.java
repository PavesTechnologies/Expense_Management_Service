package com.expense_management_service.integration.rms;

import com.expense_management_service.integration.rms.dto.RmsApiResponse;
import com.expense_management_service.integration.rms.dto.RmsClientResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link RmsClient} implementation backed by the shared {@code rmsRestClient} {@link RestClient}
 * bean (see {@code RestClientConfig}), whose request interceptor sets the EMS service-account
 * token from {@link RmsServiceTokenProvider} rather than forwarding the caller's own JWT.
 */
@Component
@RequiredArgsConstructor
public class RmsClientImpl implements RmsClient {

    private final RestClient rmsRestClient;
    private final RmsServiceTokenProvider rmsServiceTokenProvider;

    @Override
    public Optional<RmsClientResponse> getClient(UUID clientId) {
        try {
            return fetchClient(clientId);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                // Cached service-account token may have expired between the safety-margin check
                // and the actual call, or been revoked server-side — refresh once and retry.
                rmsServiceTokenProvider.invalidate();
                return fetchClient(clientId);
            }
            // RMS confirmed to return 400 (not 404) for "client not found" on this endpoint.
            if (isNotFound(ex.getStatusCode())) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private Optional<RmsClientResponse> fetchClient(UUID clientId) {
        try {
            RmsApiResponse<RmsClientResponse> response = rmsRestClient.get()
                    .uri("/api/client/{id}", clientId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<RmsApiResponse<RmsClientResponse>>() {});
            return Optional.ofNullable(response).map(RmsApiResponse::data);
        } catch (RestClientResponseException ex) {
            if (isNotFound(ex.getStatusCode())) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private boolean isNotFound(HttpStatusCode status) {
        return status.value() == 400 || status.value() == 404;
    }
}
