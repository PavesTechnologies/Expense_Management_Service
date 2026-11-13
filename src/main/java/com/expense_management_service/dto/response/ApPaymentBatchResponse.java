package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ApPaymentBatchResponse(
        UUID batchId,
        String bankReference,
        BigDecimal totalAmount,
        String status,
        List<AllocationResult> allocations
) {
    /** {@code reportPaid} is true only when this allocation settled the report's remaining payable amount. */
    public record AllocationResult(UUID reportId, BigDecimal amount, BigDecimal payableBefore, BigDecimal payableAfter, boolean reportPaid) {
    }
}
