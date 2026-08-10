package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApprovalDomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApprovalWebSocketEventListenerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;

    private ApprovalWebSocketEventListener listener;

    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new ApprovalWebSocketEventListener(messagingTemplate, expenseReportRepository, approvalAssignmentRepository);
    }

    private ApprovalAssignment assignment(String approverId, AssignmentStatus status) {
        ApprovalLevelInstance instance = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).build();
        return ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).levelInstance(instance)
                .approverId(approverId).status(status).build();
    }

    @Test
    void onApprovalEvent_notifiesReportOwner_andEveryActiveApprover() {
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).employeeId("owner-1").build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId)).thenReturn(List.of(
                assignment("approver-1", AssignmentStatus.ACTIVE),
                assignment("approver-2", AssignmentStatus.ACTIVE),
                assignment("approver-3", AssignmentStatus.COMPLETED)));

        listener.onApprovalEvent(new ApprovalDomainEvent("REPORT_SUBMITTED", reportId, "detail"));

        verify(messagingTemplate).convertAndSendToUser(eq("owner-1"), eq("/queue/report-updates"), any(ApprovalDomainEvent.class));
        verify(messagingTemplate).convertAndSendToUser(eq("approver-1"), eq("/queue/approval-queue-updates"), any(ApprovalDomainEvent.class));
        verify(messagingTemplate).convertAndSendToUser(eq("approver-2"), eq("/queue/approval-queue-updates"), any(ApprovalDomainEvent.class));
        verify(messagingTemplate, never()).convertAndSendToUser(eq("approver-3"), any(), any());
    }

    @Test
    void onApprovalEvent_notifiesEachDistinctApproverOnlyOnce() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.empty());
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId)).thenReturn(List.of(
                assignment("approver-1", AssignmentStatus.ACTIVE),
                assignment("approver-1", AssignmentStatus.ACTIVE)));

        listener.onApprovalEvent(new ApprovalDomainEvent("LEVEL_ACTIVATED", reportId, "detail"));

        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq("approver-1"), eq("/queue/approval-queue-updates"), any(ApprovalDomainEvent.class));
    }

    @Test
    void onApprovalEvent_neverThrows_whenMessagingFails() {
        when(expenseReportRepository.findById(reportId)).thenThrow(new RuntimeException("broker down"));

        listener.onApprovalEvent(new ApprovalDomainEvent("REPORT_APPROVED", reportId, "detail"));
        // No assertion beyond "did not throw" - the whole point is this never propagates.
    }
}
