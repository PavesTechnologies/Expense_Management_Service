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

    /** True when the advance holds money Finance carried forward that no later advance has taken in yet. */
    public static boolean holdsCarryForward(BigDecimal heldAmount) {
        return heldAmount != null && heldAmount.signum() > 0;
    }

    /** The carried-forward amount still held after {@code moved} of it went into another advance; null once none is left. */
    public static BigDecimal readyAfterMove(BigDecimal readyAmount, BigDecimal moved) {
        if (readyAmount == null) {
            return null;
        }
        BigDecimal left = readyAmount.subtract(moved == null ? BigDecimal.ZERO : moved);
        return left.signum() > 0 ? left : null;
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
        return totalAllocation(type, eligibleOutstanding, BigDecimal.ZERO, amount);
    }

    /**
     * Total advance amount the request covers. Cash Advance + Outstanding adds the eligible outstanding; Cash Advance +
     * Carry Forward adds only the balance Finance has already carried forward and is holding.
     */
    public static BigDecimal totalAllocation(CashAdvanceRequestType type, BigDecimal eligibleOutstanding,
                                             BigDecimal heldCarryForward, BigDecimal amount) {
        return switch (typeOrDefault(type)) {
            case ADVANCE_PLUS_OUTSTANDING -> eligibleOutstanding.add(amount);
            case ADVANCE_PLUS_CARRY_FORWARD -> (heldCarryForward == null ? BigDecimal.ZERO : heldCarryForward).add(amount);
            case COMPLETE_NEW -> amount;
        };
    }

    /** True for the request types where {@code amount} is only the new cash, with a balance added on top. */
    public static boolean addsBalance(CashAdvanceRequestType type) {
        CashAdvanceRequestType t = typeOrDefault(type);
        return t == CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING || t == CashAdvanceRequestType.ADVANCE_PLUS_CARRY_FORWARD;
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
