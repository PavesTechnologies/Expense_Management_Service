package com.expense_management_service.service.impl;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The one place that changes a cash advance's balance and status on behalf of a settlement event, and
 * writes the audit record for that change.
 *
 * <p>Callers must save the advance and this audit entry inside the same transaction. The callers that use
 * this ledger are class-level {@code @Transactional}, so the balance update and the audit row commit or roll
 * back together.
 */
@Service
@RequiredArgsConstructor
public class CashAdvanceBalanceLedger {

    private final AuditLogRepository auditLogRepository;
    private final CashAdvanceBalanceCalculator balanceCalculator;
    private final ExpenseReportRepository expenseReportRepository;

    /**
     * The status an advance should have at this balance: closed only at zero and only when no linked report still owes
     * the employee a reimbursement. Every path that changes an advance's status goes through this rule.
     */
    public String statusFor(CashAdvance advance, BigDecimal balance, int decimals) {
        // Overdue is derived from the records, not stored as a one-off flag. An open balance after the expense window has
        // ended is OVERDUE on every recompute, so a later balance change cannot silently clear it.
        if (balance != null && balance.signum() > 0 && CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.now())) {
            return "OVERDUE";
        }
        return CashAdvanceBalanceRules.statusAfterBalance(balance, decimals, hasUnpaidReimbursement(advance));
    }

    /** True when any report linked to the advance still has a reimbursable amount that is not fully paid. */
    public boolean hasUnpaidReimbursement(CashAdvance advance) {
        if (advance == null || advance.getAdvanceId() == null) {
            return false;
        }
        List<ExpenseReport> reports = expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId());
        return reports != null && reports.stream().anyMatch(CashAdvanceBalanceRules::reimbursementOwed);
    }

    /**
     * Re-derives the advance's status from its current balance and the reimbursements now linked to it. Call this after
     * a reimbursement is paid, so an advance waiting on that payment closes once the last one is paid.
     */
    public void refreshStatus(CashAdvance advance, String actor, String reason) {
        if (advance == null) {
            return;
        }
        // Recompute from the records. The stored column is only a cache and must not drive the status.
        applyBalance(advance, balanceCalculator.outstanding(advance), "CASH_ADVANCE_STATUS_RECHECKED", actor, reason);
    }

    /**
     * Sets the advance's balance and the status that balance implies, and records the change if either changed.
     *
     * @param advance          the advance to update (the caller saves it)
     * @param requestedBalance the balance after the event; rounded to the currency and rejected if negative
     * @param action           audit action, e.g. {@code CASH_ADVANCE_BALANCE_RECALCULATED}
     * @param actor            who or what caused the change
     * @param reason           why the change happened, for the audit trail
     */
    public void applyBalance(CashAdvance advance, BigDecimal requestedBalance, String action, String actor, String reason) {
        int decimals = advance.getCurrency() != null ? advance.getCurrency().getDecimalPlaces() : 2;

        BigDecimal oldBalance = advance.getOutstandingBalance();
        String oldStatus = advance.getStatus();

        BigDecimal newBalance = CashAdvanceBalanceRules.requireNonNegative(requestedBalance, decimals);
        String newStatus = statusFor(advance, newBalance, decimals);

        advance.setOutstandingBalance(newBalance);
        advance.setStatus(newStatus);
        CashAdvanceBalanceRules.stampSettlement(advance, newStatus, actor);

        // Compare numerically, so 15000 and 15000.00 are the same balance
        boolean balanceChanged = oldBalance == null || oldBalance.compareTo(newBalance) != 0;
        boolean statusChanged = !newStatus.equals(oldStatus);
        if (balanceChanged || statusChanged) {
            recordChange("CashAdvance", advance.getAdvanceId(), action,
                    "Balance: " + oldBalance + ", Status: " + oldStatus,
                    "Balance: " + newBalance + ", Status: " + newStatus,
                    actor, reason);
        }
    }

    /** Records a change that doesn't go through the balance rule, such as an adjustment amount being capped. */
    public void recordChange(String entityName, UUID entityId, String action, String oldValue, String newValue,
                             String actor, String reason) {
        auditLogRepository.save(AuditLog.builder()
                .entityName(entityName)
                .entityId(entityId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .performedBy(actor)
                .performedAt(LocalDateTime.now())
                .reason(reason)
                .build());
    }
}
