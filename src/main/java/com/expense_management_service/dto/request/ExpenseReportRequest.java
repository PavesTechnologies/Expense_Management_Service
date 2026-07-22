package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseReportRequest(
        @NotBlank @Size(max = 255) String reportNumber,
        @NotBlank @Size(max = 255) String employeeId,
        @NotBlank @Size(max = 255) String title,
        String businessPurpose,
        @NotNull UUID costCenterId,
        @Size(max = 255) String reportStatus,
        @NotNull UUID currencyId,
        BigDecimal totalAmount,
        BigDecimal reimbursableAmount,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        LocalDateTime closedAt
) {
}
