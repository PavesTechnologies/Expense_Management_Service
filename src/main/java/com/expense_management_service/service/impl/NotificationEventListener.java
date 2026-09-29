package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.expense_management_service.service.NotificationService.ROLE_AP;
import static com.expense_management_service.service.NotificationService.ROLE_FINANCE;

/**
 * Turns workflow events ({@link ApprovalDomainEvent}) into notifications for whoever owns the next
 * step - the third consumer of the same events, alongside the RabbitMQ and WebSocket listeners.
 * <p>
 * Runs after the workflow's transaction commits, in its own transaction, and swallows every
 * failure: a notification problem must never undo or block an approval, verification or payment.
 * Events that carry no news for anyone (e.g. a single line-item verification) are ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final Pattern BY = Pattern.compile("by=(\\S+)");
    private static final Pattern APPROVER = Pattern.compile("approver=(\\S+)");
    private static final Pattern COMMENT = Pattern.compile("comment=(.*)$");

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final EmployeeCacheRepository employeeCacheRepository;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onWorkflowEvent(ApprovalDomainEvent event) {
        try {
            expenseReportRepository.findById(event.reportId()).ifPresent(report -> dispatch(event, new Ctx(report, event.eventType())));
        } catch (Exception ex) {
            log.warn("Could not create notifications for {} on report {}", event.eventType(), event.reportId(), ex);
        }
    }

    private void dispatch(ApprovalDomainEvent event, Ctx c) {
        String detail = event.detail() == null ? "" : event.detail();
        switch (event.eventType()) {
            case "REPORT_SUBMITTED" -> c.toOwner(NotificationCategory.PENDING, "Expense report submitted",
                    "Your report %s was submitted and is now with your approver.", "Pending manager approval", null);

            case "LEVEL_ACTIVATED" -> {
                // Approval levels go to the assigned approvers personally; the Finance level goes to
                // the whole Finance team via FINANCE_VERIFICATION_ACTIVATED instead.
                List<ApprovalAssignment> active = activeApprovalAssignments(c.report);
                if (active.isEmpty()) return;
                String levelName = active.get(0).getLevelInstance().getLevelName();
                String status = "Pending approval" + (levelName != null ? " · " + levelName : "");
                active.forEach(a -> c.toEmployee(a.getApproverId(), NotificationCategory.ACTION_REQUIRED,
                        "Expense report %s requires your approval",
                        "Submitted by %s for %s.".formatted(c.ownerName(), c.amountText()), status,
                        "Review expense", "/expense-management/approvals"));
                if (active.get(0).getLevelInstance().getLevelOrder() != null && active.get(0).getLevelInstance().getLevelOrder() > 1) {
                    c.toOwner(NotificationCategory.PENDING, "Moved to the next approval level",
                            "Your report %s passed the previous approval and is now" + (levelName != null ? " at " + levelName : " with the next approver") + ".",
                            status, null);
                }
            }

            case "SEQUENTIAL_ENTRY_ADVANCED" -> activeApprovalAssignments(c.report).forEach(a ->
                    c.toEmployee(a.getApproverId(), NotificationCategory.ACTION_REQUIRED,
                            "Expense report %s requires your approval",
                            "It's your turn to review this report from %s (%s).".formatted(c.ownerName(), c.amountText()),
                            "Pending approval", "Review expense", "/expense-management/approvals"));

            case "REPORT_AWAITING_CORRECTION" -> c.toOwner(NotificationCategory.ACTION_REQUIRED, "Correction requested on %s",
                    "Your approver sent part of report %s back. Fix the flagged items and resubmit.",
                    "Awaiting correction", "Fix report");

            case "VERIFICATION_QUERY_RAISED" -> {
                c.toOwner(NotificationCategory.ACTION_REQUIRED, "Finance has a query on %s",
                        "Finance needs something fixed on report %s (for example a receipt or amount). Correct it and resubmit.",
                        "Returned by Finance", "Fix report");
                c.toRole(ROLE_FINANCE, NotificationCategory.INFO, "Query raised on %s",
                        "%s sent report %%s back to %s for correction.".formatted(nameOf(match(BY, detail)), c.ownerName()),
                        "Awaiting employee correction", null, "/expense-management/finance");
            }

            case "REPORT_RESUMED" -> {
                c.toOwner(NotificationCategory.PENDING, "Report %s resubmitted",
                        "Your corrections to report %s were resubmitted for review.", "Resubmitted", null);
                activeApprovalAssignments(c.report).forEach(a -> c.toEmployee(a.getApproverId(), NotificationCategory.ACTION_REQUIRED,
                        "Expense report %s was corrected and resubmitted",
                        "%s fixed the items you flagged (%s).".formatted(c.ownerName(), c.amountText()),
                        "Pending approval", "Review expense", "/expense-management/approvals"));
            }

            case "VERIFICATION_QUERY_RESOLVED" -> c.toRole(ROLE_FINANCE, NotificationCategory.ACTION_REQUIRED,
                    "Expense report %s resubmitted after your query",
                    "%s corrected the report (%s). It's back in the verification queue.".formatted(c.ownerName(), c.amountText()),
                    "Pending finance verification", "Verify expense", "/expense-management/finance");

            case "REPORT_REJECTED" -> {
                String comment = match(COMMENT, detail);
                c.toOwner(NotificationCategory.FAILED, "Expense report %s was rejected",
                        "Report %s was rejected by " + nameOf(match(BY, detail)) + "." + (comment != null && !comment.isBlank() ? " Reason: " + comment : ""),
                        "Rejected", "View report");
            }

            case "FINANCE_VERIFICATION_ACTIVATED" -> {
                c.toRole(ROLE_FINANCE, NotificationCategory.ACTION_REQUIRED, "Expense report %s is ready for verification",
                        "Approved report from %s (%s) is waiting in the Finance queue.".formatted(c.ownerName(), c.amountText()),
                        "Pending finance verification", "Verify expense", "/expense-management/finance");
                c.toOwner(NotificationCategory.PENDING, "Report %s approved, now with Finance",
                        "Your approver approved report %s. Finance is verifying it next.", "Pending finance verification", null);
            }

            case "FINANCE_VERIFICATION_COMPLETED" -> {
                c.toOwner(NotificationCategory.COMPLETED, "Finance verification completed for %s",
                        "Finance verified every expense on report %s.", "Verified by Finance", null);
                c.toRole(ROLE_FINANCE, NotificationCategory.COMPLETED, "Verification completed for %s",
                        "All expenses on %s's report (%s) are verified.".formatted(c.ownerName(), c.amountText()),
                        "Verified", null, "/expense-management/finance");
            }

            case "REPORT_APPROVED" -> c.toOwner(NotificationCategory.COMPLETED, "Expense report %s approved",
                    "Report %s is fully approved.", "Approved", "View report");

            case "REPORT_APPROVED_FOR_PAYMENT" -> {
                c.toRole(ROLE_AP, NotificationCategory.ACTION_REQUIRED, "Expense report %s is ready for payment",
                        "Reimburse %s %s. Finance has verified the report.".formatted(c.ownerName(), c.amountText()),
                        "Approved for payment", "Process payment", "/expense-management/ap-payments/queue");
                c.toOwner(NotificationCategory.PENDING, "Payment initiated for %s",
                        "Report %s was sent to Accounts Payable for reimbursement.", "With AP for payment", null);
            }

            case "REPORT_INVOICE_HANDOFF" -> c.toRole(ROLE_FINANCE, NotificationCategory.ACTION_REQUIRED,
                    "Client-billable report %s ready for invoice handoff",
                    "%s's report includes client-billable expenses (%s) to hand to the invoicing team.".formatted(c.ownerName(), c.amountText()),
                    "Invoice handoff pending", "Hand off", "/expense-management/client-billing");

            case "PAYMENT_COMPLETED" -> {
                c.toOwner(NotificationCategory.COMPLETED, "Payment completed for %s",
                        "You've been reimbursed " + c.amountText() + " for report %s.", "Paid", "View report");
                c.toRole(ROLE_AP, NotificationCategory.COMPLETED, "Payment recorded for %s",
                        "%s marked %s's reimbursement (%s) as paid.".formatted(nameOf(match(BY, detail)), c.ownerName(), c.amountText()),
                        "Paid", null, "/expense-management/ap-payments/queue");
            }

            case "SLA_REMINDER" -> {
                String approver = match(APPROVER, detail);
                // The scheduler re-fires hourly while overdue; remind at most once a day.
                if (approver == null || notificationRepository.existsByEmployeeIdAndEventTypeAndReportIdAndSentAtAfter(
                        approver, "SLA_REMINDER", c.report.getReportId(), LocalDateTime.now().minusHours(24))) {
                    return;
                }
                c.toEmployee(approver, NotificationCategory.ACTION_REQUIRED, "Reminder: %s is overdue for your approval",
                        "Report from %s (%s) is past its approval due date.".formatted(c.ownerName(), c.amountText()),
                        "Overdue", "Review expense", "/expense-management/approvals");
            }

            default -> {
                // LINE_ITEM_REVIEWED, LINE_ITEM_VERIFIED, SPLIT_REVIEWED, LEVEL_COMPLETED, REPORT_RESTARTED,
                // REPORT_RECALLED, REPORT_CANCELLED: intermediate or self-initiated - covered by the
                // report-level events above, or nothing anyone else needs to act on.
            }
        }
    }

    private List<ApprovalAssignment> activeApprovalAssignments(ExpenseReport report) {
        return approvalAssignmentRepository.findByLevelInstance_Report_ReportId(report.getReportId()).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getLevelInstance().getLevelType() == LevelType.APPROVAL)
                .toList();
    }

    private String nameOf(String employeeId) {
        if (employeeId == null) return "someone";
        return employeeCacheRepository.findByEmployeeId(employeeId).map(this::displayName).orElse(employeeId);
    }

    private String displayName(EmployeeCache e) {
        String name = ((e.getFirstName() == null ? "" : e.getFirstName().trim()) + " "
                + (e.getLastName() == null ? "" : e.getLastName().trim())).trim();
        return name.isEmpty() ? e.getEmployeeId() : name;
    }

    private static String match(Pattern p, String detail) {
        Matcher m = p.matcher(detail);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Per-event context: the report, its owner's name and base-currency amount, loaded once. */
    private final class Ctx {
        final ExpenseReport report;
        final String eventType;
        private String ownerName;
        private BigDecimal amount;

        Ctx(ExpenseReport report, String eventType) {
            this.report = report;
            this.eventType = eventType;
        }

        String ownerName() {
            if (ownerName == null) ownerName = nameOf(report.getEmployeeId());
            return ownerName;
        }

        BigDecimal amount() {
            if (amount == null) amount = Optional.ofNullable(expenseLineItemRepository.sumBaseAmountByReportId(report.getReportId())).orElse(BigDecimal.ZERO);
            return amount;
        }

        String amountText() {
            return baseCurrencyCode + " " + String.format("%,.2f", amount());
        }

        String ownerLink() {
            return "/expense-management/expenses/reports/" + report.getReportId();
        }

        /** Title/message may contain "%s", replaced by the report number (plain replace - never String.format on text that can hold user input). */
        void toOwner(NotificationCategory category, String title, String message, String status, String action) {
            toEmployee(report.getEmployeeId(), category, title, message, status, action, ownerLink(), null);
        }

        void toEmployee(String employeeId, NotificationCategory category, String title, String message,
                        String status, String action, String link) {
            toEmployee(employeeId, category, title, message, status, action, link, ownerName());
        }

        private void toEmployee(String employeeId, NotificationCategory category, String title, String message,
                                String status, String action, String link, String actor) {
            notificationService.notifyEmployee(employeeId, draft(category, title, message, status, action, link, actor));
        }

        void toRole(String role, NotificationCategory category, String title, String message,
                    String status, String action, String link) {
            notificationService.notifyRole(role, draft(category, title, message, status, action, link, ownerName()));
        }

        private NotificationDraft draft(NotificationCategory category, String title, String message,
                                        String status, String action, String link, String actor) {
            String number = report.getReportNumber();
            return NotificationDraft.builder()
                    .category(category)
                    .eventType(eventType)
                    .title(title.replace("%s", number))
                    .message(message.replace("%s", number))
                    .reportId(report.getReportId())
                    .reportNumber(number)
                    .actorName(actor)
                    .amount(amount())
                    .currencyCode(baseCurrencyCode)
                    .statusLabel(status)
                    .actionLabel(action)
                    .link(link)
                    .build();
        }
    }
}
