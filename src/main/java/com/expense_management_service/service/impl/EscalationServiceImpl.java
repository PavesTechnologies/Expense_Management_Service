package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.EscalationRunResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.EscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Reminders-only (§5.4) - deliberately does NOT reassign anything, unlike EP06's escalation, which
 * auto-skip-leveled to the approver's manager. A human (the approver themselves, or Admin) must set
 * a delegate to actually move a stalled assignment; this sweep only makes the staleness visible.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class EscalationServiceImpl implements EscalationService {

    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final ApprovalEventPublisher approvalEventPublisher;

    @Override
    public EscalationRunResponse runReminderSweep() {
        LocalDateTime now = LocalDateTime.now();
        var overdue = approvalAssignmentRepository.findByStatusAndDueDateBefore(AssignmentStatus.ACTIVE, now);

        for (ApprovalAssignment assignment : overdue) {
            approvalEventPublisher.publish("SLA_REMINDER", assignment.getLevelInstance().getReport().getReportId(),
                    "approver=" + assignment.getApproverId() + " overdueSince=" + assignment.getDueDate());
        }

        String note = overdue.isEmpty()
                ? "No overdue approval assignments found."
                : "Scanned " + overdue.size() + " overdue assignment(s): reminder fired for each. No auto-reassignment - "
                        + "the approver or Admin must set a delegate to move a stalled assignment.";
        log.info("SLA reminder sweep finished: scanned={}", overdue.size());
        return new EscalationRunResponse(overdue.size(), overdue.size(), now, note);
    }
}
