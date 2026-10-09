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
 * @param reimbursedAmount paid out
 */
public record TeamExpenseSummaryResponse(
        String baseCurrencyCode,
        int memberCount,
        long reportCount,
        BigDecimal claimedAmount,
        BigDecimal inProgressAmount,
        BigDecimal approvedAmount,
        BigDecimal reimbursedAmount,
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
            LocalDateTime lastSubmittedAt
    ) {
    }
}
