package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PolicyGroupMemberRequest(
        @NotBlank String employeeId
) {
}
