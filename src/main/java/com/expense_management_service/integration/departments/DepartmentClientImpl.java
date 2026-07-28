package com.expense_management_service.integration.departments;

import com.expense_management_service.integration.departments.dto.DepartmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * {@link DepartmentClient} implementation backed by the shared {@code employeeOnboardingRestClient}
 * {@link RestClient} bean (see {@code RestClientConfig}).
 * <p>
 * <b>Assumption:</b> {@code GET /ems/masters/departments} is the only Department endpoint specified —
 * there is no confirmed get-by-id route, so existence checks fetch the full list and check membership.
 */
@Component
@RequiredArgsConstructor
public class DepartmentClientImpl implements DepartmentClient {

    private final RestClient employeeOnboardingRestClient;

    @Override
    public List<DepartmentResponse> getAllDepartments() {
        DepartmentResponse[] departments = employeeOnboardingRestClient.get()
                .uri("/ems/masters/departments")
                .retrieve()
                .body(DepartmentResponse[].class);
        return departments == null ? List.of() : List.of(departments);
    }

    @Override
    public boolean existsById(UUID departmentUuid) {
        return getAllDepartments().stream()
                .anyMatch(department -> departmentUuid.equals(department.departmentUuid()));
    }
}
