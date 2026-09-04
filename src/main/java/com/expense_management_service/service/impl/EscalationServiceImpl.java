package com.expense_management_service.service.impl;

import com.expense_management_service.common.BusinessDayCalculator;
import com.expense_management_service.dto.response.EscalationRunResponse;
import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.enums.TaskStatus;
import com.expense_management_service.repository.ApprovalTaskRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.EscalationService;
import com.expense_management_service.service.SlaPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class EscalationServiceImpl implements EscalationService {

    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final ApprovalTaskRepository approvalTaskRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final DelegationService delegationService;
    private final SlaPolicyService slaPolicyService;

    @Override
    public EscalationRunResponse runEscalationSweep() {
        LocalDateTime now = LocalDateTime.now();
        var overdue = approvalTaskRepository.findByTaskStatusAndDueDateBefore(TaskStatus.PENDING, now);

        int escalatedCount = 0;
        int stalledCount = 0;

        for (ApprovalTask task : overdue) {
            Optional<String> target = resolveEscalationTarget(task.getApproverId());
            if (target.isPresent()) {
                escalateTo(task, target.get(), now);
                escalatedCount++;
            } else {
                log.warn("SLA breach on task {} (approver {}) but no escalation target is configured "
                                + "(no active delegate, no manager on file in EmployeeCache) - leaving assigned, "
                                + "repeating this reminder on every future sweep until resolved",
                        task.getTaskId(), task.getApproverId());
                stalledCount++;
            }
        }

        String note = overdue.isEmpty()
                ? "No overdue approval tasks found."
                : "Scanned " + overdue.size() + " overdue task(s): " + escalatedCount + " escalated, "
                        + stalledCount + " left in place with no escalation target.";
        log.info("SLA escalation sweep finished: scanned={}, escalated={}, stalled={}", overdue.size(), escalatedCount, stalledCount);
        return new EscalationRunResponse(overdue.size(), escalatedCount, stalledCount, now, note);
    }

    /** Active delegate first, then a "skip-level" escalation to the approver's own manager. */
    private Optional<String> resolveEscalationTarget(String approverId) {
        return delegationService.resolveActiveDelegate(approverId)
                .or(() -> employeeCacheRepository.findByEmployeeId(approverId)
                        .map(EmployeeCache::getManagerEmployeeId)
                        .filter(managerId -> managerId != null && !managerId.isBlank()));
    }

    /**
     * The overdue task becomes a closed, terminal record (ESCALATED); a new task - same
     * groupId/level/mode/cycle, so it participates in level-completion exactly as its
     * predecessor would have - is created for the escalation target with a fresh SLA window.
     */
    private void escalateTo(ApprovalTask overdueTask, String newApproverId, LocalDateTime now) {
        overdueTask.setTaskStatus(TaskStatus.ESCALATED);
        overdueTask.setActionedAt(now);
        overdueTask.setActedBy(SYSTEM_ACTOR);
        approvalTaskRepository.save(overdueTask);

        int slaDays = slaPolicyService.resolveSlaBusinessDays();
        ApprovalTask replacement = ApprovalTask.builder()
                .report(overdueTask.getReport())
                .approverId(newApproverId)
                .approvalLevel(overdueTask.getApprovalLevel())
                .groupId(overdueTask.getGroupId())
                .approvalMode(overdueTask.getApprovalMode())
                .submissionCycle(overdueTask.getSubmissionCycle())
                .taskStatus(TaskStatus.PENDING)
                .assignedAt(now)
                .dueDate(BusinessDayCalculator.addBusinessDays(now, slaDays))
                .comments("Escalated from " + overdueTask.getApproverId() + " (task " + overdueTask.getTaskId() + ") after SLA breach")
                .build();
        approvalTaskRepository.save(replacement);

        log.info("Escalated task {} (approver {}) to {} - new task {}",
                overdueTask.getTaskId(), overdueTask.getApproverId(), newApproverId, replacement.getTaskId());
    }
}
