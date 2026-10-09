package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, UUID> {

    /** Duplicate-title validation — a title only needs to be unique for one employee within one fiscal period. */
    Optional<ExpenseReport> findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(
            String employeeId, String fiscalYear, String title);

        /** Scopes the paginated expense history to the requesting Employee's reports. */
    Page<ExpenseReport> findByEmployeeId(String employeeId, Pageable pageable);

    @Query("""
            SELECT r FROM ExpenseReport r
            WHERE r.employeeId = :employeeId
              AND (:status IS NULL OR r.reportStatus = :status)
              AND (:query IS NULL OR :query = ''
                   OR lower(r.title) LIKE lower(concat('%', :query, '%'))
                   OR lower(r.reportNumber) LIKE lower(concat('%', :query, '%')))
            """)
    Page<ExpenseReport> searchByEmployeeIdAndStatusAndQuery(
            @Param("employeeId") String employeeId,
            @Param("status") ReportStatus status,
            @Param("query") String query,
            Pageable pageable);

    /**
     * Paginated "My History" (§14): a single query rather than a UNION, since both outcome
     * branches are predicates against the same {@code ExpenseReport} row - approved (an EXISTS
     * against a COMPLETED assignment for this employee, regardless of the report's *current*
     * overall status - a Manager's own completed level must show up here even while the report has
     * since moved on to Finance Verification or a later level, not only once the whole chain
     * finishes) or rejected ({@code rejectedBy} match, unchanged - that's still a terminal action
     * this exact employee took). {@code includeApproved}/{@code includeRejected} let the caller ask
     * for either, both, or (defensively) neither without a third query shape. Ordered by whichever
     * decision timestamp actually applies to that row: this employee's own completed-assignment
     * timestamp for an approved row (falling back to the report's approvedAt if, for any reason, no
     * matching assignment timestamp is found), or rejectedAt for a rejected row.
     */
    @Query("""
            SELECT r FROM ExpenseReport r
            WHERE (:includeApproved = true
                   AND EXISTS (SELECT 1 FROM ApprovalAssignment a
                               WHERE a.levelInstance.report = r AND a.approverId = :employeeId
                                 AND a.status = com.expense_management_service.enums.AssignmentStatus.COMPLETED))
               OR (:includeRejected = true AND r.rejectedBy = :employeeId)
            ORDER BY COALESCE(
                (SELECT MAX(a2.updatedAt) FROM ApprovalAssignment a2
                 WHERE a2.levelInstance.report = r AND a2.approverId = :employeeId
                   AND a2.status = com.expense_management_service.enums.AssignmentStatus.COMPLETED),
                r.approvedAt, r.rejectedAt) DESC
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

    /**
     * The Finance Verification queue (§8): role+status based, matching how the AP Payment queue
     * above already works - any Finance Executive can act once a report reaches this status, with
     * no per-report/per-cost-center assignment required to make it visible.
     */
    Page<ExpenseReport> findByReportStatus(ReportStatus reportStatus, Pageable pageable);

    /**
     * The AP Payment history tabs: any {@code paymentRoutingStatus} other than {@code
     * APPROVED_FOR_PAYMENT} (that one is the pending queue, see {@link
     * #findByReportStatusAndPaymentRoutingStatus}) or {@code NONE} (not a payment-routing outcome
     * at all - most approved reports with no Finance Verification level).
     */
    Page<ExpenseReport> findByPaymentRoutingStatus(PaymentRoutingStatus paymentRoutingStatus, Pageable pageable);

    long countByPaymentRoutingStatus(PaymentRoutingStatus paymentRoutingStatus);

    long countByReportStatusAndPaymentRoutingStatus(ReportStatus reportStatus, PaymentRoutingStatus paymentRoutingStatus);

    long countByPaymentRoutingStatusAndPaymentCompletedAtGreaterThanEqual(
            PaymentRoutingStatus paymentRoutingStatus, java.time.LocalDateTime since);

    /**
     * A manager's team view: reports belonging to any of {@code employeeIds} (never empty - callers
     * short-circuit an empty team), excluding {@code excludedStatus} (Drafts stay private to their owner).
     */
    @Query("""
            SELECT r FROM ExpenseReport r
            WHERE r.employeeId IN :employeeIds
              AND r.reportStatus <> :excludedStatus
              AND (:status IS NULL OR r.reportStatus = :status)
              AND (:query IS NULL OR :query = ''
                   OR lower(r.title) LIKE lower(concat('%', :query, '%'))
                   OR lower(r.reportNumber) LIKE lower(concat('%', :query, '%')))
            """)
    Page<ExpenseReport> searchByEmployeeIds(
            @Param("employeeIds") Collection<String> employeeIds,
            @Param("excludedStatus") ReportStatus excludedStatus,
            @Param("status") ReportStatus status,
            @Param("query") String query,
            Pageable pageable);

    List<ExpenseReport> findByEmployeeIdInAndReportStatusNot(Collection<String> employeeIds, ReportStatus reportStatus);
}
