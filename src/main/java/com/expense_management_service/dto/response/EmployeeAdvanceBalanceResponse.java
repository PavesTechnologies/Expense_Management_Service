package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What an approver sees before approving an advance: the request itself, and the employee's other advances that are
 * still open, with what each still owes. Read-only.
 */
public record EmployeeAdvanceBalanceResponse(
        UUID advanceId,
        String employeeId,
        String currencyCode,
        BigDecimal requestedAmount,
        BigDecimal pendingBalanceTotal,
        List<OpenAdvance> otherOpenAdvances,
        /** Configured company limit on the employee's total exposure. Null when no limit is configured. */
        BigDecimal exposureLimit,
        /** Approved or pending advances that are not disbursed yet, excluding this one. */
        BigDecimal committedAmount,
        /** Outstanding + committed + this request. */
        BigDecimal potentialExposure,
        /** How far the potential exposure is above the limit. Zero when within it. */
        BigDecimal excessOverLimit,
        boolean exceedsLimit,
        /** What the manager recorded on this request. Null when the manager has not decided yet. */
        ManagerDecision managerDecision
) {
    public record OpenAdvance(UUID advanceId, String title, String status, BigDecimal amount, BigDecimal outstandingBalance) {
    }

    /**
     * The manager's recommendation, shown to Finance before Finance approves. The recommendation is not binding: Finance
     * decides on the final approval. {@code exposureExceptionAuthorized} is true once Finance has approved the request.
     */
    public record ManagerDecision(
            /** FULL_ADVANCE, REDUCED, SET_OFF or DEFERRED. */
            String treatment,
            String justification,
            String decidedBy,
            LocalDateTime decidedAt,
            /** Set when the treatment is SET_OFF: the earlier advance whose balance is recommended for set-off. */
            UUID setOffSourceAdvanceId,
            BigDecimal setOffAmount,
            /** Manager's reason for approving above the exposure limit. Null when within the limit. */
            String exposureExceptionJustification,
            String exposureExceptionBy,
            LocalDateTime exposureExceptionAt,
            boolean exposureExceptionAuthorized
    ) {
    }
}
