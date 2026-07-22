package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ApprovalDelegationRequest(
        @NotBlank @Size(max = 255) String delegatorId,
        @NotBlank @Size(max = 255) String delegateId,
        LocalDate startDate,
        LocalDate endDate,
        @Size(max = 255) String status
) {
}
