package com.expense_management_service.service.impl;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalDomainEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseReportAuditListenerTest {

    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private CurrentUserService currentUserService;
    @InjectMocks
    private ExpenseReportAuditListener listener;

    private final UUID reportId = UUID.randomUUID();

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void rejection_isAuditedAgainstTheReport_withTheSignedInActorAndComment() {
        when(currentUserService.getEmployeeId()).thenReturn("5100099");

        listener.onWorkflowEvent(new ApprovalDomainEvent("REPORT_REJECTED", reportId, "by=5100014 comment=Missing hotel bill"));

        AuditLog row = saved();
        assertThat(row.getEntityName()).isEqualTo("ExpenseReport");
        assertThat(row.getEntityId()).isEqualTo(reportId);
        assertThat(row.getAction()).isEqualTo("REPORT_REJECTED");
        // The signed-in user wins over by=: a delegate acting for the approver is recorded as themselves.
        assertThat(row.getPerformedBy()).isEqualTo("5100099");
        assertThat(row.getReason()).isEqualTo("Missing hotel bill");
        assertThat(row.getSource()).isEqualTo(AuditSource.EMPLOYEE);
        assertThat(row.getPerformedAt()).isNotNull();
    }

    @Test
    void financeEvent_isSourcedFinance() {
        when(currentUserService.getEmployeeId()).thenReturn("5100050");

        listener.onWorkflowEvent(new ApprovalDomainEvent("REPORT_APPROVED_FOR_PAYMENT", reportId, null));

        assertThat(saved().getSource()).isEqualTo(AuditSource.FINANCE);
    }

    @Test
    void withoutSignedInUser_fallsBackToBy() {
        when(currentUserService.getEmployeeId()).thenThrow(new IllegalStateException("no JWT"));

        listener.onWorkflowEvent(new ApprovalDomainEvent("REPORT_RECALLED", reportId, "by=5100014"));
        AuditLog withBy = saved();
        assertThat(withBy.getPerformedBy()).isEqualTo("5100014");
        assertThat(withBy.getSource()).isEqualTo(AuditSource.EMPLOYEE);
    }

    @Test
    void schedulerEvent_withNoActor_isSystem() {
        when(currentUserService.getEmployeeId()).thenThrow(new IllegalStateException("no JWT"));

        listener.onWorkflowEvent(new ApprovalDomainEvent("LEVEL_ACTIVATED", reportId, null));

        AuditLog row = saved();
        assertThat(row.getPerformedBy()).isNull();
        assertThat(row.getSource()).isEqualTo(AuditSource.SYSTEM);
    }

    @Test
    void remindersAndEventsAuditedElsewhere_areSkipped() {
        listener.onWorkflowEvent(new ApprovalDomainEvent("SLA_REMINDER", reportId, null));
        listener.onWorkflowEvent(new ApprovalDomainEvent("PAYMENT_COMPLETED", reportId, "by=1"));
        listener.onWorkflowEvent(new ApprovalDomainEvent("CASH_ADVANCE_ADJUSTED", reportId, null));

        verify(auditLogRepository, never()).save(any());
    }
}
