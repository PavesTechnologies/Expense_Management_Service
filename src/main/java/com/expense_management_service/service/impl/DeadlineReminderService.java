package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Emails an employee once when the expense deadline of an open cash advance is 7 days away. The reminder time comes
 * from {@link CashAdvanceExpenseWindow#reminderDue}. The send is recorded on the advance, so it is never repeated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadlineReminderService {

    static final String EMAIL_DEADLINE_REMINDER = "CASH_ADVANCE_DEADLINE_REMINDER";

    /** Statuses in which an expense report can still be linked to the advance. */
    static final List<String> OPEN_STATUSES = List.of(
            "DISBURSED", "IN_PROGRESS", "EXPENSE_SUBMITTED", "EXPENSE_VERIFIED",
            "SETTLEMENT_PENDING", "PARTIALLY_ADJUSTED", "PARTIALLY_SETTLED");

    private static final String MY_ADVANCES_PATH = "/expense-management/cash-advance/my";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final CashAdvanceRepository cashAdvanceRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final EmailOutboxService emailOutbox;

    /** Base URL of the portal, used for the link in the email. Blank means the link is left out. */
    @Value("${expense.mail.app-base-url:}")
    private String appBaseUrl;

    /** Queues the reminders that are due at {@code now}. Returns how many were queued. */
    @Transactional
    public int sendDueReminders(LocalDateTime now) {
        int queued = 0;
        for (CashAdvance advance : cashAdvanceRepository.findByStatusInAndDeadlineReminderSentAtIsNull(OPEN_STATUSES)) {
            LocalDateTime deadline = CashAdvanceExpenseWindow.deadline(advance);
            LocalDateTime due = CashAdvanceExpenseWindow.reminderDue(advance);
            if (deadline == null || due == null || now.isBefore(due) || now.isAfter(deadline)) {
                continue;
            }
            if (queue(advance, deadline, now)) {
                queued++;
            }
        }
        return queued;
    }

    private boolean queue(CashAdvance advance, LocalDateTime deadline, LocalDateTime now) {
        String advanceRef = String.valueOf(advance.getAdvanceId());
        EmployeeCache employee = employeeCacheRepository.findByEmployeeId(advance.getEmployeeId()).orElse(null);
        String mail = employee == null || employee.getWorkEmail() == null || employee.getWorkEmail().isBlank()
                ? null
                : employee.getWorkEmail();
        if (mail == null) {
            // Not marked as sent, so the reminder goes out once the employee has an address.
            log.warn("Cash advance {}: employee {} has no work email, deadline reminder not sent",
                    advanceRef, advance.getEmployeeId());
            return false;
        }
        String deadlineText = deadline.toLocalDate().format(DATE);
        String currency = advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : "";
        String link = appBaseUrl == null || appBaseUrl.isBlank()
                ? null
                : appBaseUrl.replaceAll("/+$", "") + MY_ADVANCES_PATH;
        String body = String.join("\n",
                "Dear " + (employee == null || employee.getFirstName() == null ? "employee" : employee.getFirstName()) + ",",
                "",
                "The expense deadline for your cash advance is in 7 days.",
                "",
                "ADVANCE DETAILS",
                "- Advance ID: " + advanceRef,
                "- Title: " + (advance.getTitle() == null ? "-" : advance.getTitle()),
                "- Amount: " + currency + " " + advance.getAmount().toPlainString(),
                "- Expense deadline: " + deadlineText,
                "",
                "ACTION REQUIRED",
                "Submit your expense report with its receipts before the deadline" + (link == null ? "." : ": " + link),
                "If you cannot make the deadline, ask Finance to extend it.",
                "",
                "Regards,",
                "Expense Management",
                "");
        emailOutbox.enqueue(mail,
                "Expense deadline in 7 days: cash advance " + advanceRef,
                body, EMAIL_DEADLINE_REMINDER, advanceRef);
        advance.setDeadlineReminderSentAt(now);
        cashAdvanceRepository.save(advance);
        return true;
    }
}
