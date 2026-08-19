package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ApprovalAssignmentRepository extends JpaRepository<ApprovalAssignment, UUID> {

    List<ApprovalAssignment> findByLevelInstance_InstanceId(UUID instanceId);

    /** Backs the SLA reminder sweep (§5.4/§7.3) - reminders-only, never auto-reassigned. */
    List<ApprovalAssignment> findByStatusAndDueDateBefore(AssignmentStatus status, java.time.LocalDateTime dueDate);

    /** Every assignment ever created for a report, across all submission cycles - backs ownership checks and "my history" queries. */
    List<ApprovalAssignment> findByLevelInstance_Report_ReportId(UUID reportId);

    /**
     * Distinct, paginated report ids with a currently-actionable assignment for any of
     * {@code approverIds} (self + active delegators, resolved by {@code DelegationService.
     * resolveApproverIdsActingFor} before this is called) - backs paginated "My Queue" (§14).
     * Grouping (rather than DISTINCT + ORDER BY on a non-selected column, which some JPA
     * providers reject) also gives a stable, deterministic order: oldest-assigned report first.
     */
    @Query("""
            SELECT a.levelInstance.report.reportId FROM ApprovalAssignment a
            WHERE a.status = :status AND a.approverId IN :approverIds
            GROUP BY a.levelInstance.report.reportId
            ORDER BY MIN(a.assignedAt) ASC
            """)
    Page<UUID> findDistinctReportIdsByStatusAndApproverIdIn(
            @Param("status") AssignmentStatus status, @Param("approverIds") Collection<String> approverIds, Pageable pageable);

    /** Same as {@link #findDistinctReportIdsByStatusAndApproverIdIn}, additionally scoped to one level type - backs Finance's own "My Queue" so a Finance-role approver's Manager-level assignments (if any) never leak into it, and vice versa. */
    @Query("""
            SELECT a.levelInstance.report.reportId FROM ApprovalAssignment a
            WHERE a.status = :status AND a.approverId IN :approverIds AND a.levelInstance.levelType = :levelType
            GROUP BY a.levelInstance.report.reportId
            ORDER BY MIN(a.assignedAt) ASC
            """)
    Page<UUID> findDistinctReportIdsByStatusAndApproverIdInAndLevelType(
            @Param("status") AssignmentStatus status, @Param("approverIds") Collection<String> approverIds,
            @Param("levelType") LevelType levelType, Pageable pageable);
}
