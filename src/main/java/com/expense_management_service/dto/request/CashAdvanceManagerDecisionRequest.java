package com.expense_management_service.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The manager's decision on a cash advance request.
 *
 * <ul>
 *   <li>{@code APPROVE_FULL}: approve the full requested amount (Option A). Needs {@code justification} when the employee's
 *       exposure is above the limit; that exception is then authorized by Finance at final approval.</li>
 *   <li>{@code APPROVE_REDUCED}: approve a lower amount in {@code approvedAmount}. Needs {@code justification}.</li>
 *   <li>{@code SET_OFF}: approve the full amount and recommend that {@code setOffAmount} of the employee's outstanding
 *       balance on {@code setOffSourceAdvanceId} is set off against it (Option B). Finance proposes and approves the
 *       carry-forward. Disbursement is blocked until it is applied.</li>
 *   <li>{@code DEFER}: hold the request, to be resubmitted later. Needs {@code justification}.</li>
 *   <li>{@code REJECT}: reject with {@code justification} as the reason.</li>
 * </ul>
 */
public record CashAdvanceManagerDecisionRequest(
        String decision,
        BigDecimal approvedAmount,
        String justification,
        UUID setOffSourceAdvanceId,
        BigDecimal setOffAmount
) {
}
