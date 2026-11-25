package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Finance's end-to-end money view: for the whole organization, each department and each employee, every rupee of cash
 * advances and expense claims by where it is now, the tax on the claims, and (departments and total) the budget left.
 * Every amount is in {@code baseCurrencyCode}.
 *
 * @param fiscalYear the budget year used for {@link Budget} (e.g. "2026-2027")
 * @param from       optional start of the range (advances by request date, reports by submit date); null = all time
 * @param to         optional end of the range; null = up to today
 */
public record EmployeeMoneyReportResponse(
        String baseCurrencyCode,
        String fiscalYear,
        LocalDate from,
        LocalDate to,
        Group total,
        List<Group> departments,
        List<Group> employees,
        /** Per cost center: key = cost center id, name = cost center name, departmentKey = its department. */
        List<Group> costCenters,
        /** Cash advances paid out and expenses claimed per day, week or month across the range (see {@link Point}). */
        List<Point> timeline,
        /** Tax checks on the claimed lines (see {@link TaxReview}). */
        TaxReview taxReview
) {

    /**
     * @param linesAwaitingReview lines in reports now waiting for Finance whose tax check failed (MISMATCH or
     *                            REQUIRES_FINANCE_REVIEW); Finance must confirm the tax before verifying. Not limited to the range.
     * @param scannedLines        lines in the range whose receipt was scanned and a tax amount read from it
     * @param mismatchedLines     of those, lines whose entered tax differs from the receipt (OCR_MISMATCH)
     */
    public record TaxReview(long linesAwaitingReview, long scannedLines, long mismatchedLines) {
    }

    /**
     * One step of the timeline. {@code period} is the first day of the bucket (ISO date); {@code granularity} is DAY,
     * WEEK (Monday to Sunday) or MONTH, chosen from the length of the range so the chart has a readable number of points.
     */
    public record Point(String period, String label, String granularity, BigDecimal advancesPaidOut, BigDecimal expensesClaimed,
                        BigDecimal tax, BigDecimal recoverableTax) {
    }

    /**
     * One row of the view: the whole organization, one department or one employee.
     *
     * @param key           department UUID ("unassigned" when unknown), employee ID, or "total"
     * @param departmentKey for an employee, their department; null otherwise
     * @param name          employee name from the employee cache; null for departments (the client resolves names)
     * @param employeeCount employees with any advance or claim in this group
     * @param budget        departments and total only; null for employees and when no budget is set
     */
    public record Group(String key, String departmentKey, String name, long employeeCount,
                        Advances advances, Expenses expenses, Tax tax, Budget budget) {
    }

    /**
     * Cash advances, in the order the money moves. Requested and approved count every live request; the rest only
     * advances that were paid out. held = paidOut + carriedIn = used + salaryDeducted + refunded + carriedOut + outstanding.
     *
     * @param pendingApproval requested, still waiting for a manager, cost center or Finance decision
     * @param approvedNotPaid approved, not paid out yet
     * @param paidOut         cash paid to the employee
     * @param carriedIn       balance carried in from an earlier advance (held, not new cash)
     * @param used            set against approved expense reports
     * @param salaryDeducted  recovered through payroll
     * @param refunded        paid back by the employee any other way
     * @param carriedOut      moved forward to a later advance
     * @param outstanding     still with the employee, to be settled
     */
    public record Advances(long count, BigDecimal requested, BigDecimal pendingApproval, BigDecimal approved,
                           BigDecimal approvedNotPaid, BigDecimal paidOut, BigDecimal carriedIn, BigDecimal used,
                           BigDecimal salaryDeducted, BigDecimal refunded, BigDecimal carriedOut, BigDecimal outstanding) {
    }

    /**
     * Expense claims (submitted reports, line-item totals). claimed = inApproval + sentBack + rejected + approved;
     * approved = coveredByAdvance + noPaymentDue + paid + due.
     *
     * @param inApproval       with a manager or Finance
     * @param sentBack         returned to the employee to correct
     * @param coveredByAdvance approved and set against the employee's cash advance (no bank payment)
     * @param noPaymentDue     approved with nothing reimbursable and no advance involved
     * @param paid             paid to the employee
     * @param due              approved, still owed to the employee
     */
    public record Expenses(long reportCount, BigDecimal claimed, BigDecimal inApproval, BigDecimal sentBack,
                           BigDecimal rejected, BigDecimal approved, BigDecimal coveredByAdvance, BigDecimal noPaymentDue,
                           BigDecimal paid, BigDecimal due) {
    }

    /**
     * Tax on the claimed lines: total, the input tax credit that can be recovered (tax x the tax code's ITC %), and the
     * rest. {@code verifiedRecoverable} is the part of {@code recoverable} Finance has verified, so it can be claimed now.
     */
    public record Tax(BigDecimal total, BigDecimal recoverable, BigDecimal nonRecoverable, BigDecimal verifiedRecoverable) {
    }

    /**
     * The fiscal year's budget of the cost centers in a department (or all of them for the total).
     * effectiveAvailable = available - reserved (ACTIVE encumbrances), the same formula the budget checks use.
     */
    public record Budget(int costCenterCount, BigDecimal budget, BigDecimal available, BigDecimal reserved,
                         BigDecimal effectiveAvailable, BigDecimal used) {
    }
}
