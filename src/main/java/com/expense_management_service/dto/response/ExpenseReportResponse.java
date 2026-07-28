package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseReportResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        String title,
        String businessPurpose,
        String fiscalYear,
        UUID costCenterId,
        String costCenterName,
        String reportStatus,
        UUID currencyId,
        String currencyCode,
        BigDecimal totalAmount,
        BigDecimal reimbursableAmount,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int lineItemCount,
        boolean editable,
        boolean deletable
) {
}
