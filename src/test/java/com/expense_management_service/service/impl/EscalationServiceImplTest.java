package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.EscalationRunResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reminders-only (§5.4) - no auto-reassignment exists anymore, unlike EP06's escalation. */
@ExtendWith(MockitoExtension.class)
class EscalationServiceImplTest {

    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private ApprovalEventPublisher approvalEventPublisher;

    private EscalationServiceImpl escalationService;

    private ApprovalAssignment overdueAssignment(String approverId) {
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).build();
        ApprovalLevelInstance instance = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).report(report).build();
        return ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .levelInstance(instance)
                .approverId(approverId)
                .status(AssignmentStatus.ACTIVE)
                .assignedAt(LocalDateTime.now().minusDays(5))
                .dueDate(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Test
    void runReminderSweep_firesAReminderEventPerOverdueAssignment_andNeverReassigns() {
        escalationService = new EscalationServiceImpl(approvalAssignmentRepository, approvalEventPublisher);
        ApprovalAssignment overdue = overdueAssignment("mgr-jane");
        when(approvalAssignmentRepository.findByStatusAndDueDateBefore(eq(AssignmentStatus.ACTIVE), any()))
                .thenReturn(List.of(overdue));

        EscalationRunResponse response = escalationService.runReminderSweep();

        assertThat(response.overdueScanned()).isEqualTo(1);
        assertThat(response.remindersSent()).isEqualTo(1);
        assertThat(overdue.getStatus()).isEqualTo(AssignmentStatus.ACTIVE); // untouched - never reassigned
        assertThat(overdue.getApproverId()).isEqualTo("mgr-jane");
        verify(approvalEventPublisher).publish(eq("SLA_REMINDER"), eq(overdue.getLevelInstance().getReport().getReportId()), anyString());
        verify(approvalAssignmentRepository, never()).save(any());
    }

    @Test
    void runReminderSweep_returnsZeroCounts_whenNoOverdueAssignments() {
        escalationService = new EscalationServiceImpl(approvalAssignmentRepository, approvalEventPublisher);
        when(approvalAssignmentRepository.findByStatusAndDueDateBefore(eq(AssignmentStatus.ACTIVE), any()))
                .thenReturn(List.of());

        EscalationRunResponse response = escalationService.runReminderSweep();

        assertThat(response.overdueScanned()).isZero();
        assertThat(response.remindersSent()).isZero();
        verify(approvalEventPublisher, never()).publish(any(), any(), any());
    }
}
