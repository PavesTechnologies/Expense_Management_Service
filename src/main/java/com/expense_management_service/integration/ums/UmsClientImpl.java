package com.expense_management_service.integration.ums;

import com.expense_management_service.integration.ums.dto.EmployeeResponse;
import com.expense_management_service.integration.ums.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * {@link UmsClient} implementation backed by the shared {@code umsRestClient}
 * {@link RestClient} bean (see {@code RestClientConfig}).
 * <p>
 * <b>Assumption:</b> the endpoint paths below follow common UMS REST conventions
 * and are not confirmed by the integration spec — only the JWKS endpoint was
 * specified there. Adjust these paths to match UMS's actual API contract.
 */
@Component
@RequiredArgsConstructor
public class UmsClientImpl implements UmsClient {

    private final RestClient umsRestClient;

    @Override
    public UserProfileResponse getCurrentUser() {
        return umsRestClient.get()
                .uri("/general_user/profile")
                .retrieve()
                .body(UserProfileResponse.class);
    }

    @Override
    public UserProfileResponse getUser(UUID uuid) {
        return umsRestClient.get()
                .uri("/api/v1/users/{uuid}", uuid)
                .retrieve()
                .body(UserProfileResponse.class);
    }

    @Override
    public EmployeeResponse getEmployee(UUID uuid) {
        return umsRestClient.get()
                .uri("/api/v1/employees/{uuid}", uuid)
                .retrieve()
                .body(EmployeeResponse.class);
    }
}
