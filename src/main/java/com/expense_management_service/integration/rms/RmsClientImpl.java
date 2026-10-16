package com.expense_management_service.integration.rms;

import com.expense_management_service.integration.rms.dto.RmsActiveClientResponse;
import com.expense_management_service.integration.rms.dto.RmsApiResponse;
import com.expense_management_service.integration.rms.dto.RmsClientResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RmsClient} implementation backed by the shared {@code rmsRestClient} {@link RestClient}
 * bean (see {@code RestClientConfig}), whose request interceptor forwards the caller's own JWT.
 */
@Component
@RequiredArgsConstructor
public class RmsClientImpl implements RmsClient {

    private final RestClient rmsRestClient;

    @Override
    public Optional<RmsClientResponse> getClient(UUID clientId) {
        try {
            RmsApiResponse<RmsClientResponse> response = rmsRestClient.get()
                    .uri("/api/client/{id}", clientId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<RmsApiResponse<RmsClientResponse>>() {});
            return Optional.ofNullable(response).map(RmsApiResponse::data);
        } catch (RestClientResponseException ex) {
            // RMS confirmed to return 400 (not 404) for "client not found" on this endpoint.
            if (isNotFound(ex.getStatusCode())) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public List<RmsActiveClientResponse> getActiveClients() {
        RmsApiResponse<List<RmsActiveClientResponse>> response = rmsRestClient.get()
                .uri("/api/client/get-active-clients")
                .retrieve()
                .body(new ParameterizedTypeReference<RmsApiResponse<List<RmsActiveClientResponse>>>() {});
        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data();
    }

    private boolean isNotFound(HttpStatusCode status) {
        return status.value() == 400 || status.value() == 404;
    }
}
