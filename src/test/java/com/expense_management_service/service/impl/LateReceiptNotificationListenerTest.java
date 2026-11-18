package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.event.LateReceiptRejectedEvent;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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

/** A rejected late receipt is reported, for information only, to the managers who approved the report. */
@ExtendWith(MockitoExtension.class)
class LateReceiptNotificationListenerTest {

    private static final String EMPLOYEE = "5100014";
    private static final String MANAGER = "MGR001";
    private static final String FINANCE_USER = "FIN001";

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private LateReceiptNotificationListener listener;

    private UUID reportId;
    private Receipt receipt;

    @BeforeEach
    void setUp() {
        reportId = UUID.randomUUID();
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).employeeId(EMPLOYEE).reportNumber("EXP-001").build();
        ExpenseLineItem line = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report)
                .merchantName("City Cab").expenseDate(LocalDate.of(2026, 11, 5)).amount(new BigDecimal("400.00")).build();
        receipt = Receipt.builder().receiptId(UUID.randomUUID()).report(report).lineItem(line).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_LATE).verificationStatus(Receipt.VERIFICATION_REJECTED)
                .verificationNote("The receipt is for a different date").build();
    }

    private static ApprovalAssignment assignment(String approverId, LevelType type, AssignmentStatus status) {
        ApprovalLevelInstance level = ApprovalLevelInstance.builder().levelType(type).build();
        return ApprovalAssignment.builder().approverId(approverId).levelInstance(level).status(status).build();
    }

    @Test
    void notifiesTheManagerWhoApproved_asInformationOnly() {
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId)).thenReturn(List.of(
                assignment(MANAGER, LevelType.APPROVAL, AssignmentStatus.COMPLETED)));

        listener.onLateReceiptRejected(new LateReceiptRejectedEvent(receipt.getReceiptId(), FINANCE_USER));

        ArgumentCaptor<NotificationDraft> draft = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(notificationService).notifyEmployee(eq(MANAGER), draft.capture());
        assertThat(draft.getValue().category()).isEqualTo(NotificationCategory.INFO);
        assertThat(draft.getValue().eventType()).isEqualTo(LateReceiptNotificationListener.EVENT_TYPE);
        assertThat(draft.getValue().reportId()).isEqualTo(reportId);
        assertThat(draft.getValue().message()).contains("City Cab").contains("No action is needed")
                .contains("The receipt is for a different date");
    }

    @Test
    void skipsFinanceLevels_unfinishedAssignments_theReviewer_andTheEmployee_andNotifiesEachManagerOnce() {
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId)).thenReturn(List.of(
                assignment(MANAGER, LevelType.APPROVAL, AssignmentStatus.COMPLETED),
                assignment(MANAGER, LevelType.APPROVAL, AssignmentStatus.COMPLETED),
                assignment("FIN002", LevelType.FINANCE_VERIFICATION, AssignmentStatus.COMPLETED),
                assignment("MGR002", LevelType.APPROVAL, AssignmentStatus.SUPERSEDED),
                assignment(FINANCE_USER, LevelType.APPROVAL, AssignmentStatus.COMPLETED),
                assignment(EMPLOYEE, LevelType.APPROVAL, AssignmentStatus.COMPLETED)));

        listener.onLateReceiptRejected(new LateReceiptRejectedEvent(receipt.getReceiptId(), FINANCE_USER));

        verify(notificationService).notifyEmployee(eq(MANAGER), any());
        verify(notificationService, never()).notifyEmployee(eq("FIN002"), any());
        verify(notificationService, never()).notifyEmployee(eq("MGR002"), any());
        verify(notificationService, never()).notifyEmployee(eq(FINANCE_USER), any());
        verify(notificationService, never()).notifyEmployee(eq(EMPLOYEE), any());
    }

    @Test
    void aNotificationFailure_isSwallowed_soTheRejectionStands() {
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId)).thenReturn(List.of(
                assignment(MANAGER, LevelType.APPROVAL, AssignmentStatus.COMPLETED)));
        org.mockito.Mockito.doThrow(new RuntimeException("inbox down"))
                .when(notificationService).notifyEmployee(anyString(), any());

        assertThatCode(() -> listener.onLateReceiptRejected(new LateReceiptRejectedEvent(receipt.getReceiptId(), FINANCE_USER)))
                .doesNotThrowAnyException();
    }
}
