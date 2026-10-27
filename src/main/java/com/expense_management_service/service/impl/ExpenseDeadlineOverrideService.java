package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.DeadlineOverrideRequest;
import com.expense_management_service.dto.request.DeadlineOverrideRequest.DeadlineOverrideAction;
import com.expense_management_service.dto.response.DeadlineOverrideResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Finance overrides of a report's submission deadline. An EXTEND moves the deadline later; a REOPEN lifts the check.
 * Both need a reason, are written to the audit log, and apply to one report (ordinary or advance-linked).
 * Reporting-period validation is not affected: it still runs on create, update and submit.
 */
@Service
@RequiredArgsConstructor
public class ExpenseDeadlineOverrideService {

    static final int MIN_REASON_LENGTH = 10;

    private final ExpenseReportRepository expenseReportRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    @Value("${expense.submission.deadline-days:30}")
    private int submissionDeadlineDays = SubmissionDeadlinePolicy.DEFAULT_DEADLINE_DAYS;

    @Transactional
    public DeadlineOverrideResponse apply(UUID reportId, DeadlineOverrideRequest request) {
        ExpenseReport report = expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense report not found: " + reportId));
        String actor = currentUserService.getEmployeeId();

        if (actor != null && actor.equals(report.getEmployeeId())) {
            throw new BusinessRuleViolationException("You cannot override the submission deadline of your own report.");
        }
        if (report.getReportStatus() != ReportStatus.DRAFT) {
            throw new BusinessRuleViolationException("Deadline overrides apply only to reports that are still in draft. Current status: "
                    + report.getReportStatus());
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException("Give a reason of at least " + MIN_REASON_LENGTH + " characters for the override.");
        }

        LocalDate today = LocalDate.now();
        String oldValue = "extendedUntil=" + report.getExpenseDeadlineExtendedUntil()
                + ", reopened=" + report.isExpenseDeadlineReopened();

        if (request.action() == DeadlineOverrideAction.EXTEND) {
            LocalDate until = request.extendedUntil();
            if (until == null) {
                throw new IllegalArgumentException("Enter the new submission date for the extension.");
            }
            if (until.isBefore(today)) {
                throw new BusinessRuleViolationException("The extended deadline cannot be in the past.");
            }
            LocalDate current = currentDeadline(report);
            if (current != null && until.isBefore(current)) {
                throw new BusinessRuleViolationException("An extension cannot shorten the current deadline (" + current + ").");
            }
            report.setExpenseDeadlineExtendedUntil(until);
        } else {
            if (request.extendedUntil() != null) {
                throw new IllegalArgumentException("A reopen does not take a date.");
            }
            report.setExpenseDeadlineReopened(true);
        }

        expenseReportRepository.save(report);
        auditLogRepository.save(AuditLog.builder()
                .entityName("ExpenseReport")
                .entityId(report.getReportId())
                .action(request.action() == DeadlineOverrideAction.EXTEND ? "DEADLINE_EXTENDED" : "DEADLINE_REOPENED")
                .oldValue(oldValue)
                .newValue("extendedUntil=" + report.getExpenseDeadlineExtendedUntil()
                        + ", reopened=" + report.isExpenseDeadlineReopened())
                .performedBy(actor)
                .performedAt(LocalDateTime.now())
                .reason(reason)
                .build());

        return new DeadlineOverrideResponse(
                report.getReportId(),
                request.action().name(),
                report.getExpenseDeadlineExtendedUntil(),
                report.isExpenseDeadlineReopened(),
                currentDeadline(report),
                actor);
    }

    /** The deadline the submit gate would enforce now, or null when no clock is running. */
    private LocalDate currentDeadline(ExpenseReport report) {
        if (report.isExpenseDeadlineReopened()) {
            return null;
        }
        boolean linked = report.getCashAdvance() != null;
        LocalDate disbursedOn = linked && report.getCashAdvance().getDisbursedAt() != null
                ? report.getCashAdvance().getDisbursedAt().toLocalDate()
                : null;
        return SubmissionDeadlinePolicy.effectiveDeadline(report.getTripEndDate(), linked, disbursedOn,
                report.getExpenseDeadlineExtendedUntil(), submissionDeadlineDays);
    }
}
