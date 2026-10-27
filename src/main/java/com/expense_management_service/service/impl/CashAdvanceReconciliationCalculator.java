package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.CashAdvanceRepayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure calculations for reconciliation. No database access and no writes: it takes the ledger rows and returns the
 * expected balance at the end of a period, the stored balance, and the difference.
 *
 * <p>Expected balance at period end = amount + carried in − carried out − repaid − adjusted, counting only movements dated
 * before the day after the period end, and only advances disbursed by then. Reversed carry-forwards count until their
 * reversal date. A difference is flagged only when no movement dated after the period end explains it; the stored
 * balance is the current value, so later movements would otherwise look like discrepancies.
 *
 * <p>Financial-year periods need the financial-year start month, which Finance has not confirmed. Only MONTHLY periods
 * are available until {@code expense.finance-year-start-month} is set.
 */
public final class CashAdvanceReconciliationCalculator {

    public static final String MONTHLY = "MONTHLY";
    public static final String QUARTERLY = "QUARTERLY";
    public static final String HALF_YEARLY = "HALF_YEARLY";
    public static final String FINANCIAL_YEAR = "FINANCIAL_YEAR";

    private CashAdvanceReconciliationCalculator() {
    }

    public record Period(String periodType, LocalDate start, LocalDate end) {
    }

    public record LineSnapshot(CashAdvance advance, String employeeId, BigDecimal expected, BigDecimal stored,
                               BigDecimal discrepancy, boolean flagged, int laterMovements) {
    }

    /** The period that contains {@code anchor}. */
    public static Period period(String periodType, LocalDate anchor, int financeYearStartMonth) {
        String type = periodType == null ? "" : periodType.trim().toUpperCase(Locale.ROOT);
        if (anchor == null) {
            throw new IllegalArgumentException("Enter a date inside the period to reconcile.");
        }
        if (MONTHLY.equals(type)) {
            LocalDate start = anchor.withDayOfMonth(1);
            return new Period(type, start, start.plusMonths(1).minusDays(1));
        }
        if (!QUARTERLY.equals(type) && !HALF_YEARLY.equals(type) && !FINANCIAL_YEAR.equals(type)) {
            throw new IllegalArgumentException("Unknown period type: " + periodType);
        }
        if (financeYearStartMonth < 1 || financeYearStartMonth > 12) {
            throw new BusinessRuleViolationException(
                    "Finance has not confirmed the financial-year start, so only monthly reconciliation is available.");
        }
        int fyStartYear = anchor.getMonthValue() >= financeYearStartMonth ? anchor.getYear() : anchor.getYear() - 1;
        LocalDate fyStart = LocalDate.of(fyStartYear, financeYearStartMonth, 1);
        int monthsIntoYear = (anchor.getYear() * 12 + anchor.getMonthValue()) - (fyStartYear * 12 + financeYearStartMonth);
        return switch (type) {
            case QUARTERLY -> {
                LocalDate start = fyStart.plusMonths(3L * (monthsIntoYear / 3));
                yield new Period(type, start, start.plusMonths(3).minusDays(1));
            }
            case HALF_YEARLY -> {
                LocalDate start = fyStart.plusMonths(6L * (monthsIntoYear / 6));
                yield new Period(type, start, start.plusMonths(6).minusDays(1));
            }
            default -> new Period(type, fyStart, fyStart.plusMonths(12).minusDays(1));
        };
    }

    /**
     * One snapshot line per advance that was disbursed by the period end. Read-only: nothing in the inputs is changed.
     */
    public static List<LineSnapshot> snapshot(LocalDate periodEnd, List<CashAdvance> advances,
                                              List<CashAdvanceCarryForward> carryForwards,
                                              List<CashAdvanceRepayment> repayments,
                                              List<CashAdvanceAdjustment> adjustments) {
        LocalDateTime endExclusive = periodEnd.plusDays(1).atStartOfDay();
        List<LineSnapshot> lines = new ArrayList<>();

        for (CashAdvance advance : advances) {
            UUID id = advance.getAdvanceId();
            if (advance.getDisbursedAt() == null || !advance.getDisbursedAt().isBefore(endExclusive)) {
                continue;
            }

            BigDecimal carriedIn = BigDecimal.ZERO;
            BigDecimal carriedOut = BigDecimal.ZERO;
            int laterMovements = 0;
            for (CashAdvanceCarryForward cf : carryForwards) {
                boolean isDestination = cf.getDestinationAdvance() != null && id.equals(cf.getDestinationAdvance().getAdvanceId());
                boolean isSource = cf.getSourceAdvance() != null && id.equals(cf.getSourceAdvance().getAdvanceId());
                if (!isDestination && !isSource) {
                    continue;
                }
                if (cf.getAppliedAt() != null && !cf.getAppliedAt().isBefore(endExclusive)) {
                    laterMovements++;
                }
                if (cf.getReversedAt() != null && !cf.getReversedAt().isBefore(endExclusive)) {
                    laterMovements++;
                }
                boolean appliedByEnd = cf.getAppliedAt() != null && cf.getAppliedAt().isBefore(endExclusive);
                boolean reversedAfterEnd = cf.getReversedAt() == null || !cf.getReversedAt().isBefore(endExclusive);
                if (appliedByEnd && reversedAfterEnd) {
                    if (isDestination) carriedIn = carriedIn.add(cf.getAmount());
                    if (isSource) carriedOut = carriedOut.add(cf.getAmount());
                }
            }

            BigDecimal repaid = BigDecimal.ZERO;
            for (CashAdvanceRepayment r : repayments) {
                if (r.getCashAdvance() == null || !id.equals(r.getCashAdvance().getAdvanceId()) || r.getRepaidAt() == null) {
                    continue;
                }
                if (r.getRepaidAt().isBefore(endExclusive)) {
                    repaid = repaid.add(r.getAmount());
                } else {
                    laterMovements++;
                }
            }

            BigDecimal adjusted = BigDecimal.ZERO;
            for (CashAdvanceAdjustment a : adjustments) {
                if (a.getCashAdvance() == null || !id.equals(a.getCashAdvance().getAdvanceId()) || a.getAdjustedAt() == null) {
                    continue;
                }
                if (a.getAdjustedAt().isBefore(endExclusive)) {
                    adjusted = adjusted.add(a.getAdjustedAmount());
                } else {
                    laterMovements++;
                }
            }

            BigDecimal expected = advance.getAmount()
                    .add(carriedIn)
                    .subtract(carriedOut)
                    .subtract(repaid)
                    .subtract(adjusted);
            BigDecimal stored = advance.getOutstandingBalance() != null ? advance.getOutstandingBalance() : advance.getAmount();
            BigDecimal discrepancy = stored.subtract(expected);
            boolean flagged = discrepancy.signum() != 0 && laterMovements == 0;
            lines.add(new LineSnapshot(advance, advance.getEmployeeId(), expected, stored, discrepancy, flagged, laterMovements));
        }
        return lines;
    }
}
