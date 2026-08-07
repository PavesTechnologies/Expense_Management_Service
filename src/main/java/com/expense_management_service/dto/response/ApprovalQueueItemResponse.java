package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One report currently awaiting this caller's action (§9.1, presence-based "My Approvals"). */
public record ApprovalQueueItemResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        BigDecimal totalAmount,
        String currencyCode,
        Integer levelOrder,
        List<PendingLineItemResponse> pendingLineItems,
        boolean eligibleForBulkApprove
) {
}
