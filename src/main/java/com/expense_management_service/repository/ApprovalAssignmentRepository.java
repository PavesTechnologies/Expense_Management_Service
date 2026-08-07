package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.enums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalAssignmentRepository extends JpaRepository<ApprovalAssignment, UUID> {

    List<ApprovalAssignment> findByLevelInstance_InstanceId(UUID instanceId);

    /** Backs "My Approvals" presence-based discoverability (§1.5/§9.1) - anyone with ≥1 ACTIVE assignment has something to act on. */
    List<ApprovalAssignment> findByApproverIdAndStatus(String approverId, AssignmentStatus status);

    /** Every currently-actionable assignment system-wide, filtered by delegation eligibility in the service layer (covers both direct approvers and active delegates). */
    List<ApprovalAssignment> findByStatus(AssignmentStatus status);

    /** Backs the SLA reminder sweep (§5.4/§7.3) - reminders-only, never auto-reassigned. */
    List<ApprovalAssignment> findByStatusAndDueDateBefore(AssignmentStatus status, java.time.LocalDateTime dueDate);
}
