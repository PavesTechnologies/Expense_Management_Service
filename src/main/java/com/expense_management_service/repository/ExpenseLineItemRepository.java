package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseLineItemRepository extends JpaRepository<ExpenseLineItem, UUID> {

    List<ExpenseLineItem> findByReport_ReportId(UUID reportId);

    /** Path-scoped lookup — guarantees a line item is only ever addressed through its own parent report. */
    Optional<ExpenseLineItem> findByLineItemIdAndReport_ReportId(UUID lineItemId, UUID reportId);

    /** Cross-report duplicate detection for {@code PolicyRuleType.DUPLICATE_EXPENSE} — same employee, category, date, amount and currency. */
    List<ExpenseLineItem> findByReport_EmployeeIdAndCategory_CategoryIdAndExpenseDateAndAmountAndCurrency_CurrencyId(
            String employeeId, UUID categoryId, LocalDate expenseDate, BigDecimal amount, UUID currencyId);

    /** Vendor-based duplicate detection for {@code PolicyRuleType.DUPLICATE_EXPENSE} — same employee, merchant, date, amount and currency, regardless of category (catches a repeat that was miscategorized). */
    List<ExpenseLineItem> findByReport_EmployeeIdAndMerchantNameIgnoreCaseAndExpenseDateAndAmountAndCurrency_CurrencyId(
            String employeeId, String merchantName, LocalDate expenseDate, BigDecimal amount, UUID currencyId);

    /**
     * Cross-employee duplicate/shared-bill detection for {@code PolicyRuleType.CROSS_EMPLOYEE_DUPLICATE_EXPENSE}
     * — a DIFFERENT employee's line item matching merchant, date, amount and currency, deliberately
     * NOT filtered by category (two employees may categorize a shared bill differently). Excludes
     * counterparty reports still in {@code DRAFT} — an unsubmitted report is unrelated noise and its
     * details shouldn't leak to another employee via this check.
     */
    List<ExpenseLineItem> findByMerchantNameIgnoreCaseAndExpenseDateAndAmountAndCurrency_CurrencyIdAndReport_EmployeeIdNotAndReport_ReportStatusNot(
            String merchantName, LocalDate expenseDate, BigDecimal amount, UUID currencyId, String employeeId, ReportStatus reportStatus);

    /** Sum of every line item's base-currency amount for a report — the report-level total is always presented in base currency. */
    @Query("select coalesce(sum(l.baseAmount), 0) from ExpenseLineItem l where l.report.reportId = :reportId")
    BigDecimal sumBaseAmountByReportId(@Param("reportId") UUID reportId);

    /**
     * Invoice-team handoff queue (Epic 8): a line item is eligible once it is actually
     * finance-verified — {@code FinanceVerificationReview.status = VERIFIED} on its own current
     * submission cycle, not merely {@code ReportStatus.APPROVED} — is client-billable, and has
     * no active {@code InvoiceSync} handoff record yet. Deliberately requires a real Finance
     * Verification pass rather than treating approval alone as sufficient (see {@code
     * ApprovalWorkflowServiceImpl}: a manager-only flow with no FINANCE_VERIFICATION level never
     * produces a {@code FinanceVerificationReview} row at all, so such a line item correctly
     * never appears here until/unless the business decides otherwise). All four filters are
     * optional (pass {@code null} to skip).
     */
    @Query("""
            SELECT DISTINCT l FROM ExpenseLineItem l, FinanceVerificationReview r
            WHERE r.lineItem = l
              AND r.status = com.expense_management_service.enums.FinanceVerificationStatus.VERIFIED
              AND r.levelInstance.submissionCycle = (
                  SELECT MAX(i2.submissionCycle) FROM ApprovalLevelInstance i2 WHERE i2.report = l.report
              )
              AND l.clientBillable = true
              AND l.report.reportStatus = com.expense_management_service.enums.ReportStatus.APPROVED
              AND NOT EXISTS (
                  SELECT 1 FROM InvoiceSync s WHERE s.lineItem = l AND s.syncStatus = 'HANDED_OFF'
              )
              AND (:clientId IS NULL OR l.resolvedClientId = :clientId)
              AND (:projectId IS NULL OR l.project.projectId = :projectId)
              AND (:startDate IS NULL OR l.expenseDate >= :startDate)
              AND (:endDate IS NULL OR l.expenseDate <= :endDate)
            ORDER BY l.expenseDate DESC
            """)
    Page<ExpenseLineItem> findEligibleForInvoiceHandoff(
            @Param("clientId") UUID clientId,
            @Param("projectId") UUID projectId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);
}
