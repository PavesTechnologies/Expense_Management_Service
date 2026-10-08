package com.expense_management_service.service.impl;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes one {@code audit_log} row for every expense-report workflow event (submitted, approved,
 * sent back, resubmitted, finance-verified, approved for payment, recalled, cancelled ...), so each
 * report keeps a permanent record of its approvals and status changes, including every
 * correction-loop round trip and every action a delegate took.
 * <p>
 * Runs BEFORE_COMMIT, inside the transaction that made the change: the audit row commits or rolls
 * back together with it, so a transition can never happen without its audit entry (the
 * notification and RabbitMQ listeners run AFTER_COMMIT, where a failure loses nothing that matters).
 * The actor is the signed-in user when there is one, which is the delegate rather than the
 * original approver when a delegate acts; scheduler-driven events fall back to the event's
 * {@code by=} and otherwise count as SYSTEM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpenseReportAuditListener {

    static final String ENTITY_NAME = "ExpenseReport";
    private static final int REASON_MAX = 500;

    private static final Pattern BY = Pattern.compile("by=(\\S+)");
    private static final Pattern COMMENT = Pattern.compile("comment=(.*)$");

    /** Not report transitions (reminders), or already audited directly by the service that made them. */
    private static final Set<String> SKIPPED = Set.of("SLA_REMINDER", "PAYMENT_COMPLETED");
    private static final String CASH_ADVANCE_PREFIX = "CASH_ADVANCE_";

    private static final Set<String> FINANCE_EVENTS = Set.of(
            "LINE_ITEM_VERIFIED", "LINE_ITEM_TAX_ADJUSTED", "VERIFICATION_QUERY_RAISED",
            "FINANCE_VERIFICATION_COMPLETED", "REPORT_APPROVED_FOR_PAYMENT", "REPORT_INVOICE_HANDOFF");

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onWorkflowEvent(ApprovalDomainEvent event) {
        String type = event.eventType();
        if (type == null || event.reportId() == null || SKIPPED.contains(type) || type.startsWith(CASH_ADVANCE_PREFIX)) {
            return;
        }
        String detail = event.detail();
        String actor = signedInEmployeeId();
        if (actor == null) {
            actor = match(BY, detail);
        }
        auditLogRepository.save(AuditLog.builder()
                .entityName(ENTITY_NAME)
                .entityId(event.reportId())
                .action(type)
                .newValue(detail)
                .reason(truncate(match(COMMENT, detail)))
                .performedBy(actor)
                .performedAt(event.occurredAt())
                .source(actor == null ? AuditSource.SYSTEM
                        : FINANCE_EVENTS.contains(type) ? AuditSource.FINANCE : AuditSource.EMPLOYEE)
                .build());
    }

    /** Null on scheduler and async threads, which have no JWT. */
    private String signedInEmployeeId() {
        try {
            return currentUserService.getEmployeeId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String match(Pattern pattern, String detail) {
        if (detail == null) {
            return null;
        }
        Matcher m = pattern.matcher(detail);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= REASON_MAX ? value : value.substring(0, REASON_MAX);
    }
}
