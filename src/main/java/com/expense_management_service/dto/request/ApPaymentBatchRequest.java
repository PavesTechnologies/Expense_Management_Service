package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One bank payment covering one or more expense reports. {@code totalAmount} must equal the sum of the allocations.
 * Each allocation pays part or all of one report's remaining payable amount.
 */
public record ApPaymentBatchRequest(
        String bankReference,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        BigDecimal totalAmount,
        String remarks,
        List<Allocation> allocations
) {
    public record Allocation(UUID reportId, BigDecimal amount) {
    }
}
