package com.expense_management_service.integration.departments.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Shape of a department record returned by Employee Onboarding's {@code GET /ems/masters/departments}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepartmentResponse(
        @JsonProperty("department_uuid") UUID departmentUuid,
        @JsonProperty("department_name") String departmentName,
        String description
) {
}
