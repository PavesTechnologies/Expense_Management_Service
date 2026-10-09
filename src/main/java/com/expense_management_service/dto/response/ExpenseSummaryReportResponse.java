package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cross-functional expense report for a date range. Every amount is in {@code baseCurrencyCode}.
 * Spend counts line items whose expense date falls in the range, on reports that were submitted and
 * not rejected or cancelled. {@code scope} is ORGANIZATION for Finance/Admin and TEAM (the caller's
 * direct reports) for a manager.
 *
 * @param byDepartment keyed by Employee Onboarding department UUID ("unassigned" when unknown) -
 *                     department names live in Employee Onboarding, so the client resolves labels
 * @param byCostCenter split-aware: a line item split across cost centers is counted under each, by
 *                     its allocated share
 */
public record ExpenseSummaryReportResponse(
        String scope,
        String baseCurrencyCode,
        LocalDate from,
        LocalDate to,
        Totals totals,
        List<Row> byMonth,
        List<Row> byCategory,
        List<Row> byCostCenter,
        List<Row> byDepartment,
        List<Row> byEmployee,
        List<Row> byStatus,
        CashAdvances cashAdvances,
        Reimbursements reimbursements
) {

    public record Totals(BigDecimal spend, long lineItemCount, long reportCount, long employeeCount,
                         BigDecimal averagePerReport) {
    }

    public record Row(String key, String label, long count, BigDecimal amount) {
    }

    /**
     * Advances requested in the range (Drafts excluded). {@code requestedAmount} leaves out cancelled
     * and rejected advances; {@code outstandingAmount} is what is still owed today on advances that
     * were paid out and not yet settled. {@code byStatus} lists every status, those two included.
     */
    public record CashAdvances(long count, BigDecimal requestedAmount, BigDecimal outstandingAmount, List<Row> byStatus) {
    }

    /**
     * {@code paid*} - reports whose payment completed in the range. {@code awaitingPayment*} - reports
     * approved for payment and not yet paid, as of now (not range-bound).
     */
    public record Reimbursements(long paidCount, BigDecimal paidAmount, long awaitingPaymentCount,
                                 BigDecimal awaitingPaymentAmount) {
    }
}
