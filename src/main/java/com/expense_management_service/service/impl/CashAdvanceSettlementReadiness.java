package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.enums.ReportStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How much of an advance's balance Finance can settle now, by salary deduction or carry forward.
 * <p>
 * Expenses on a linked report that is not approved yet will be set against the advance when it is approved, so that part
 * of the balance is held for them. Only the rest, the balance the expenses do not cover, can be settled now. Settling it
 * never closes the advance early: the held part stays on the balance until the report's approval absorbs it.
 * <p>
 * Example: INR 4,000 paid, INR 3,450 in approval. INR 550 can be settled now; INR 3,450 stays held.
 */
@Component
@RequiredArgsConstructor
public class CashAdvanceSettlementReadiness {

    /** Report statuses whose expenses are not final yet, so they are held against the advance. */
    static final Set<ReportStatus> OPEN_REPORT_STATUSES = EnumSet.of(
            ReportStatus.DRAFT, ReportStatus.SUBMITTED, ReportStatus.PENDING_APPROVAL,
            ReportStatus.PENDING_FINANCE_VERIFICATION, ReportStatus.AWAITING_CORRECTION,
            ReportStatus.POLICY_REJECTED, ReportStatus.QUERY_RAISED);

    private final CashAdvanceUtilizationCalculator utilizationCalculator;

    public CashAdvanceUtilizationResponse utilization(CashAdvance advance) {
        return utilizationCalculator.calculate(advance);
    }

    /** The part of the balance that can be settled now. */
    public BigDecimal settleable(CashAdvance advance) {
        return nz(utilization(advance).settleableAmount());
    }

    /**
     * Settling closes the advance once its balance reaches zero, so it needs at least one expense against it: an approved
     * one already set against the advance, or one on a linked report still in approval.
     */
    public void assertHasLinkedExpense(CashAdvanceUtilizationResponse u, String action) {
        if (nz(u.approvedUtilization()).signum() > 0 || nz(u.inApprovalAmount()).signum() > 0) {
            return;
        }
        throw new BusinessRuleViolationException("Link at least one expense to this advance before " + action + ". "
                + "Without a linked expense, the advance cannot be closed.");
    }

    /** Refuses an amount above what can be settled now, and says how much is held and for which reports. */
    public void assertWithinSettleable(CashAdvanceUtilizationResponse u, BigDecimal amount, String action) {
        BigDecimal settleable = nz(u.settleableAmount());
        if (amount == null || amount.compareTo(settleable) <= 0) {
            return;
        }
        String currency = u.currencyCode() == null ? "" : u.currencyCode() + " ";
        BigDecimal held = nz(u.inApprovalAmount());
        if (held.signum() <= 0) {
            throw new BusinessRuleViolationException("The amount (" + currency + plain(amount)
                    + ") cannot exceed the outstanding balance (" + currency + plain(settleable) + ").");
        }
        String reports = u.linkedReports() == null ? "" : u.linkedReports().stream()
                .filter(r -> isOpen(r.reportStatus()))
                .map(r -> r.reportNumber() + " (" + statusLabel(r.reportStatus()) + ")")
                .collect(Collectors.joining("; "));
        throw new BusinessRuleViolationException("Only " + currency + plain(settleable) + " can be " + action + " now. "
                + currency + plain(held) + " of the balance is held for expenses still in approval"
                + (reports.isEmpty() ? "" : " on " + reports)
                + ", and is set against the advance when they are approved.");
    }

    static boolean isOpen(String reportStatus) {
        if (reportStatus == null) {
            return false;
        }
        try {
            return OPEN_REPORT_STATUSES.contains(ReportStatus.valueOf(reportStatus));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String statusLabel(String status) {
        String words = status.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
