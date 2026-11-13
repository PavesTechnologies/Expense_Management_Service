package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.enums.CashAdvanceRequestType;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Pure rules for how a new cash advance request is allocated. No database access, so every rule is unit-tested directly.
 *
 * <p>Eligible rollover sources are the statuses that carry-forward accepts, less the two statuses whose expenses are still
 * waiting for approval: their balance is not yet final, so it cannot be rolled over. OVERDUE is not eligible here; an
 * overdue rollover needs a separate Finance authorisation.
 */
public final class CashAdvanceAllocationRules {

    /** Statuses whose outstanding balance may be rolled into a new advance. */
    public static final Set<String> ROLLOVER_ELIGIBLE_STATUSES = Set.of(
            "DISBURSED", "IN_PROGRESS", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED", "ADJUSTED");

    /** A justification has to say something; a single word is not enough to explain a separate advance. */
    public static final int JUSTIFICATION_MIN_LENGTH = 20;

    private CashAdvanceAllocationRules() {
    }

    public static boolean rolloverEligible(String status) {
        return status != null && ROLLOVER_ELIGIBLE_STATUSES.contains(status.toUpperCase(Locale.ROOT));
    }

    /** The request type when the caller did not send one: the behaviour before request types existed. */
    public static CashAdvanceRequestType typeOrDefault(CashAdvanceRequestType type) {
        return type == null ? CashAdvanceRequestType.COMPLETE_NEW : type;
    }

    /** A separate advance needs a reason only when the employee already owes money on another advance. */
    public static boolean justificationRequired(CashAdvanceRequestType type, BigDecimal totalOpenOutstanding) {
        return typeOrDefault(type) == CashAdvanceRequestType.COMPLETE_NEW
                && totalOpenOutstanding != null
                && totalOpenOutstanding.signum() > 0;
    }

    /** Total advance amount the request covers: the eligible balance rolled in, plus the amount needed now. */
    public static BigDecimal totalAllocation(CashAdvanceRequestType type, BigDecimal eligibleOutstanding, BigDecimal amount) {
        return typeOrDefault(type) == CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING
                ? eligibleOutstanding.add(amount)
                : amount;
    }

    /** Validates a justification against the rule. Returns the trimmed text, or null when none is needed and none given. */
    public static String validJustification(boolean required, String justification) {
        String trimmed = justification == null ? null : justification.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            if (required) {
                throw new BusinessRuleViolationException(
                        "Explain why a separate new advance is needed while an outstanding balance remains.");
            }
            return null;
        }
        if (trimmed.length() < JUSTIFICATION_MIN_LENGTH) {
            throw new BusinessRuleViolationException(
                    "The reason for a separate advance must be at least " + JUSTIFICATION_MIN_LENGTH + " characters.");
        }
        return trimmed;
    }
}
