package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tells the managers who approved a report that Finance/AP rejected a late receipt on it. The manager is informed only:
 * the receipt never changes the approved amount, so there is nothing for them to decide.
 * <p>
 * Only approvers of APPROVAL levels whose assignment completed are told; the Finance verification level is Finance's own.
 * Runs after the rejection commits, in its own transaction, and swallows failures so a notification problem never undoes
 * the rejection - the same contract as {@link NotificationEventListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LateReceiptNotificationListener {

    static final String EVENT_TYPE = "LATE_RECEIPT_REJECTED";
    private static final String APPROVALS_PATH = "/expense-management/approvals";

    private final ReceiptRepository receiptRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLateReceiptRejected(LateReceiptRejectedEvent event) {
        try {
            receiptRepository.findById(event.receiptId()).ifPresent(receipt -> notifyApprovers(receipt, event.reviewerId()));
        } catch (Exception ex) {
            log.warn("Could not notify approvers about rejected late receipt {}", event.receiptId(), ex);
        }
    }

    private void notifyApprovers(Receipt receipt, String reviewerId) {
        ExpenseReport report = receipt.getReport();
        if (report == null) {
            return;
        }
        Set<String> approvers = new LinkedHashSet<>();
        for (ApprovalAssignment a : approvalAssignmentRepository.findByLevelInstance_Report_ReportId(report.getReportId())) {
            boolean approvalLevel = a.getLevelInstance() != null && a.getLevelInstance().getLevelType() == LevelType.APPROVAL;
            if (approvalLevel && a.getStatus() == AssignmentStatus.COMPLETED && a.getApproverId() != null) {
                approvers.add(a.getApproverId());
            }
        }
        approvers.remove(reviewerId);
        approvers.remove(report.getEmployeeId());
        if (approvers.isEmpty()) {
            return;
        }

        ExpenseLineItem line = receipt.getLineItem();
        String expense = line == null ? "an expense"
                : Objects.requireNonNullElse(line.getMerchantName(), "an expense")
                        + (line.getExpenseDate() != null ? " on " + line.getExpenseDate() : "");
        String reason = receipt.getVerificationNote() != null ? " Reason: " + receipt.getVerificationNote() : "";
        NotificationDraft draft = new NotificationDraft(
                NotificationCategory.INFO,
                EVENT_TYPE,
                "Late receipt rejected on " + report.getReportNumber(),
                "Finance rejected the late receipt for " + expense + " on report " + report.getReportNumber()
                        + ", which you approved. No action is needed from you." + reason,
                report.getReportId(),
                report.getReportNumber(),
                null,
                line != null ? line.getAmount() : null,
                line != null && line.getCurrency() != null ? line.getCurrency().getCurrencyCode() : null,
                "Late receipt rejected",
                null,
                APPROVALS_PATH);
        approvers.forEach(approverId -> notificationService.notifyEmployee(approverId, draft));
    }
}
