package com.expense_management_service.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Finance's decision on a Cash Advance + Outstanding request.
 * <ul>
 *   <li>{@code rolloverAmount}: the balance to roll in. Defaults to the eligible balance (plus the overdue amount, if any).
 *       Less than the default needs {@code reductionJustification}; the rest stays outstanding on its advance.</li>
 *   <li>{@code approvedCashAmount}: the cash to pay out. Defaults to the requested additional amount; may be lower, never higher.</li>
 *   <li>{@code overdueSourceAdvanceId} with {@code overdueAmount} and {@code overdueJustification}: an overdue advance to roll
 *       from. Only a FINANCE_EXECUTIVE may give this.</li>
 * </ul>
 */
public record CashAdvanceAllocationApprovalRequest(
        BigDecimal rolloverAmount,
        BigDecimal approvedCashAmount,
        String reductionJustification,
        UUID overdueSourceAdvanceId,
        BigDecimal overdueAmount,
        String overdueJustification
) {
}
