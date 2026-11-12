package com.expense_management_service.service.impl;

import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.repository.EmployeeCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Emails an approver that a report is past its approval due date. The caller decides when to send (the same
 * once-per-24-hours rule as the in-app reminder); this class only builds and queues the mail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalReminderEmailService {

    static final String EMAIL_APPROVAL_REMINDER = "APPROVAL_REMINDER";

    private static final String APPROVALS_PATH = "/expense-management/approvals";

    private final EmailOutboxService emailOutbox;
    private final EmployeeCacheRepository employeeCacheRepository;

    /** Base URL of the portal, used for the link in the email. Blank means the link is left out. */
    @Value("${expense.mail.app-base-url:}")
    private String appBaseUrl;

    public void remind(String approverId, ExpenseReport report, String ownerName, String amountText) {
        String reportNumber = report.getReportNumber();
        String mail = employeeCacheRepository.findByEmployeeId(approverId)
                .map(EmployeeCache::getWorkEmail)
                .filter(m -> !m.isBlank())
                .orElse(null);
        if (mail == null) {
            log.warn("Report {}: approver {} has no work email, reminder mail not sent", reportNumber, approverId);
            return;
        }
        String link = appBaseUrl == null || appBaseUrl.isBlank()
                ? null
                : appBaseUrl.replaceAll("/+$", "") + APPROVALS_PATH;
        String body = String.join("\n",
                "Dear approver,",
                "",
                "The expense report below is past its approval due date and is waiting for your decision.",
                "",
                "REPORT DETAILS",
                "- Report #: " + reportNumber,
                "- Employee: " + ownerName,
                "- Amount: " + amountText,
                "",
                "ACTION REQUIRED",
                "Review and approve or reject the report" + (link == null ? "." : ": " + link),
                "",
                "Regards,",
                "Expense Management",
                "");
        emailOutbox.enqueue(mail,
                "Reminder: " + reportNumber + " is overdue for your approval",
                body, EMAIL_APPROVAL_REMINDER, reportNumber);
    }
}
