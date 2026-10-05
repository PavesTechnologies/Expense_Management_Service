package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.enums.LevelInstanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalLevelInstanceRepository extends JpaRepository<ApprovalLevelInstance, UUID> {

    List<ApprovalLevelInstance> findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(UUID reportId, Integer submissionCycle);

    Optional<ApprovalLevelInstance> findByReport_ReportIdAndSubmissionCycleAndStatus(
            UUID reportId, Integer submissionCycle, LevelInstanceStatus status);

    /** 0 if the report has never been through the approval engine yet (no prior cycle). */
    @Query("SELECT COALESCE(MAX(i.submissionCycle), 0) FROM ApprovalLevelInstance i WHERE i.report.reportId = :reportId")
    Integer findMaxSubmissionCycle(@Param("reportId") UUID reportId);

    /**
     * Finance Verification "Verified" history tab: one row per report whose current-cycle
     * FINANCE_VERIFICATION level fully completed (every line item VERIFIED). Restricted to the
     * report's own latest submission cycle so a report that was corrected and re-submitted doesn't
     * surface its stale prior-cycle instance alongside the current one.
     */
    @Query("""
            SELECT i FROM ApprovalLevelInstance i
            WHERE i.levelType = com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION
              AND i.status = com.expense_management_service.enums.LevelInstanceStatus.COMPLETED
              AND i.submissionCycle = (SELECT MAX(i2.submissionCycle) FROM ApprovalLevelInstance i2 WHERE i2.report = i.report)
            ORDER BY i.updatedAt DESC
            """)
    Page<ApprovalLevelInstance> findCompletedFinanceVerificationInstances(Pageable pageable);

    /**
     * Finance Verification "Queried" history tab: one row per report whose current-cycle
     * FINANCE_VERIFICATION level has at least one line item a Finance Executive raised a query on
     * (§Query vs Reject - there is no terminal "rejected" state at this level, a query sends the
     * report back to the employee for correction instead).
     */
    @Query("""
            SELECT DISTINCT i FROM ApprovalLevelInstance i, FinanceVerificationReview r
            WHERE r.levelInstance = i
              AND i.levelType = com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION
              AND r.status = com.expense_management_service.enums.FinanceVerificationStatus.QUERIED
              AND i.submissionCycle = (SELECT MAX(i2.submissionCycle) FROM ApprovalLevelInstance i2 WHERE i2.report = i.report)
            ORDER BY i.updatedAt DESC
            """)
    Page<ApprovalLevelInstance> findQueriedFinanceVerificationInstances(Pageable pageable);
}
