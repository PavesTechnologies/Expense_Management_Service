package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.NotificationRepository;
import com.expense_management_service.service.ApprovalDomainEvent;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationEventListenerTest {

    @Mock private NotificationService notificationService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private EmployeeCacheRepository employeeCacheRepository;

    private NotificationEventListener listener;
    private final UUID reportId = UUID.randomUUID();
    private final ExpenseReport report = ExpenseReport.builder()
            .reportId(reportId).reportNumber("EXP-1024").employeeId("5100014").build();

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(notificationService, notificationRepository, expenseReportRepository,
                expenseLineItemRepository, approvalAssignmentRepository, employeeCacheRepository);
        ReflectionTestUtils.setField(listener, "baseCurrencyCode", "INR");
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseLineItemRepository.sumBaseAmountByReportId(reportId)).thenReturn(new BigDecimal("12500"));
        when(employeeCacheRepository.findByEmployeeId("5100014"))
                .thenReturn(Optional.of(EmployeeCache.builder().employeeId("5100014").firstName("John").lastName("Doe").build()));
    }

    private ApprovalAssignment active(String approverId, LevelType type) {
        return ApprovalAssignment.builder().approverId(approverId).status(AssignmentStatus.ACTIVE)
                .levelInstance(ApprovalLevelInstance.builder().levelType(type).levelOrder(1).levelName("Manager").build())
                .build();
    }

    private NotificationDraft draftFor(String employeeId) {
        ArgumentCaptor<NotificationDraft> captor = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(notificationService).notifyEmployee(eq(employeeId), captor.capture());
        return captor.getValue();
    }

    private NotificationDraft draftForRole(String role) {
        ArgumentCaptor<NotificationDraft> captor = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(notificationService).notifyRole(eq(role), captor.capture());
        return captor.getValue();
    }

    @Test
    void levelActivated_asksTheAssignedApprover_withWhoWhatWhichStatusAndAction() {
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId))
                .thenReturn(List.of(active("5100023", LevelType.APPROVAL)));

        listener.onWorkflowEvent(new ApprovalDomainEvent("LEVEL_ACTIVATED", reportId, "level=1"));

        NotificationDraft d = draftFor("5100023");
        assertThat(d.category()).isEqualTo(NotificationCategory.ACTION_REQUIRED);
        assertThat(d.title()).isEqualTo("Expense report EXP-1024 requires your approval");
        assertThat(d.message()).contains("John Doe").contains("INR 12,500.00");
        assertThat(d.actorName()).isEqualTo("John Doe");
        assertThat(d.statusLabel()).isEqualTo("Pending approval · Manager");
        assertThat(d.actionLabel()).isEqualTo("Review expense");
        assertThat(d.link()).isEqualTo("/expense-management/approvals");
        assertThat(d.eventType()).isEqualTo("LEVEL_ACTIVATED");
    }

    @Test
    void levelActivated_forFinanceLevel_doesNotNotifyIndividually_financeTeamGetsItInstead() {
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId))
                .thenReturn(List.of(active("5100050", LevelType.FINANCE_VERIFICATION)));

        listener.onWorkflowEvent(new ApprovalDomainEvent("LEVEL_ACTIVATED", reportId, "level=2"));
        verify(notificationService, never()).notifyEmployee(anyString(), any());

        listener.onWorkflowEvent(new ApprovalDomainEvent("FINANCE_VERIFICATION_ACTIVATED", reportId, "level=2"));
        NotificationDraft finance = draftForRole(NotificationService.ROLE_FINANCE);
        assertThat(finance.category()).isEqualTo(NotificationCategory.ACTION_REQUIRED);
        assertThat(finance.link()).isEqualTo("/expense-management/finance");
        assertThat(draftFor("5100014").category()).isEqualTo(NotificationCategory.PENDING);
    }

    @Test
    void approvedForPayment_tellsApToPay_andTellsEmployeePaymentWasInitiated() {
        listener.onWorkflowEvent(new ApprovalDomainEvent("REPORT_APPROVED_FOR_PAYMENT", reportId, "reason=internal"));

        NotificationDraft ap = draftForRole(NotificationService.ROLE_AP);
        assertThat(ap.category()).isEqualTo(NotificationCategory.ACTION_REQUIRED);
        assertThat(ap.actionLabel()).isEqualTo("Process payment");
        NotificationDraft owner = draftFor("5100014");
        assertThat(owner.title()).isEqualTo("Payment initiated for EXP-1024");
        assertThat(owner.link()).isEqualTo("/expense-management/expenses/reports/" + reportId);
    }

    @Test
    void rejected_includesTheReason_evenWhenItContainsPercentSigns() {
        listener.onWorkflowEvent(new ApprovalDomainEvent("REPORT_REJECTED", reportId, "by=5100023 comment=Hotel is 50% over the limit"));

        NotificationDraft d = draftFor("5100014");
        assertThat(d.category()).isEqualTo(NotificationCategory.FAILED);
        assertThat(d.message()).contains("Reason: Hotel is 50% over the limit");
    }

    @Test
    void slaReminder_isSentAtMostOncePerDay() {
        when(notificationRepository.existsByEmployeeIdAndEventTypeAndReportIdAndSentAtAfter(eq("5100023"), eq("SLA_REMINDER"), eq(reportId), any()))
                .thenReturn(false, true);

        listener.onWorkflowEvent(new ApprovalDomainEvent("SLA_REMINDER", reportId, "approver=5100023 overdueSince=2026-09-20"));
        listener.onWorkflowEvent(new ApprovalDomainEvent("SLA_REMINDER", reportId, "approver=5100023 overdueSince=2026-09-20"));

        verify(notificationService).notifyEmployee(eq("5100023"), any());
    }

    @Test
    void failuresNeverEscapeTheListener() {
        when(expenseReportRepository.findById(reportId)).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> listener.onWorkflowEvent(new ApprovalDomainEvent("REPORT_SUBMITTED", reportId, "")))
                .doesNotThrowAnyException();
    }

    @Test
    void intermediateEvents_produceNoNotifications() {
        listener.onWorkflowEvent(new ApprovalDomainEvent("LINE_ITEM_VERIFIED", reportId, "lineItem=x by=y"));

        verify(notificationService, never()).notifyEmployee(anyString(), any());
        verify(notificationService, never()).notifyRole(anyString(), any());
    }
}
