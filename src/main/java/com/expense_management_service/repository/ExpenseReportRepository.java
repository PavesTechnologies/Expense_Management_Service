package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, UUID> {

    /** Duplicate-title validation — a title only needs to be unique for one employee within one fiscal period. */
    Optional<ExpenseReport> findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(
            String employeeId, String fiscalYear, String title);

    /** Scopes the report list to the requesting Employee's own reports. */
    List<ExpenseReport> findByEmployeeId(String employeeId);

    /**
     * Paginated "My History" (§14): a single query rather than a UNION, since both outcome
     * branches are predicates against the same {@code ExpenseReport} row - approved (an EXISTS
     * against a COMPLETED assignment for this employee) or rejected ({@code rejectedBy} match).
     * {@code includeApproved}/{@code includeRejected} let the caller ask for either, both, or
     * (defensively) neither without a third query shape. Ordered by whichever decision date
     * actually applies to that row - a report is never both approved and rejected.
     */
    @Query("""
            SELECT r FROM ExpenseReport r
            WHERE (:includeApproved = true AND r.reportStatus = com.expense_management_service.enums.ReportStatus.APPROVED
                   AND EXISTS (SELECT 1 FROM ApprovalAssignment a
                               WHERE a.levelInstance.report = r AND a.approverId = :employeeId
                                 AND a.status = com.expense_management_service.enums.AssignmentStatus.COMPLETED))
               OR (:includeRejected = true AND r.rejectedBy = :employeeId)
            ORDER BY COALESCE(r.approvedAt, r.rejectedAt) DESC
            """)
    Page<ExpenseReport> findHistoryForApprover(
            @Param("employeeId") String employeeId,
            @Param("includeApproved") boolean includeApproved,
            @Param("includeRejected") boolean includeRejected,
            Pageable pageable);

    /**
     * The AP Payment queue: internal expenses (never client-billable - those are routed to {@code
     * INVOICE_HANDOFF_PENDING} instead, never {@code APPROVED_FOR_PAYMENT}) that finished Finance
     * Verification and are awaiting external payment confirmation.
     */
    Page<ExpenseReport> findByReportStatusAndPaymentRoutingStatus(
            ReportStatus reportStatus, PaymentRoutingStatus paymentRoutingStatus, Pageable pageable);
}
