package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, UUID> {

    /** Every task ever materialised for a report, across all submission cycles - current and historical. */
    List<ApprovalTask> findByReport_ReportIdOrderByApprovalLevelAsc(UUID reportId);

    /** Sibling tasks materialised together at the same level (see EP06 plan, "snapshot at submission"). */
    List<ApprovalTask> findByGroupId(UUID groupId);

    /** Backs the "my queue" endpoint. */
    List<ApprovalTask> findByApproverIdAndTaskStatus(String approverId, TaskStatus taskStatus);
}
