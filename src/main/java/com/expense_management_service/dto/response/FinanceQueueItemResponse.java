package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One report currently in this caller's Finance queue - the Finance-level equivalent of {@code ApprovalQueueItemResponse}. */
public record FinanceQueueItemResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        BigDecimal totalAmount,
        String currencyCode,
        String costCenterName,
        String reportStatus,
        LocalDateTime submittedAt,
        Integer levelOrder,
        List<FinancePendingLineItemResponse> pendingLineItems
) {
}
