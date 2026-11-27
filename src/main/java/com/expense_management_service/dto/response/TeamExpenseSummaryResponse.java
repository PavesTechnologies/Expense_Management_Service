package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A manager's team (direct reports) with each member's submitted expenses bucketed by where they
 * stand. Amounts are in {@code baseCurrencyCode}. Drafts are excluded (private to their owner);
 * rejected and cancelled reports are counted but never added to any amount.
 *
 * @param inProgressAmount with approvers/Finance, or sent back to the employee for correction
 * @param approvedAmount   approved and not yet paid
 * @param reimbursedAmount     settled reports at their claimed total, so the three stages add up to {@code claimedAmount}
 * @param paidAmount           the part of {@code reimbursedAmount} AP paid in cash
 * @param advanceCoveredAmount the part of {@code reimbursedAmount} covered by cash advances already given
 */
public record TeamExpenseSummaryResponse(
        String baseCurrencyCode,
        int memberCount,
        long reportCount,
        BigDecimal claimedAmount,
        BigDecimal inProgressAmount,
        BigDecimal approvedAmount,
        BigDecimal reimbursedAmount,
        BigDecimal paidAmount,
        BigDecimal advanceCoveredAmount,
        List<Member> members
) {

    public record Member(
            String employeeId,
            String name,
            String email,
            String employmentStatus,
            long reportCount,
            long inProgressCount,
            long rejectedCount,
            BigDecimal claimedAmount,
            BigDecimal inProgressAmount,
            BigDecimal approvedAmount,
            BigDecimal reimbursedAmount,
            BigDecimal paidAmount,
            BigDecimal advanceCoveredAmount,
            LocalDateTime lastSubmittedAt
    ) {
    }
}
