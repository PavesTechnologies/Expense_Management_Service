package com.expense_management_service.service.impl;

import com.expense_management_service.common.BusinessDayCalculator;
import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.entity.SalaryDeductionRequest;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.repository.SalaryDeductionRequestRepository;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRecoveryCaseRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Route 2 and Route 3 of the recovery policy.
 *
 * <p>Route 2: {@link #startRefundNotice} emails the employee and starts a grace period.
 * Route 3: {@link #escalateDueCases} runs when the grace period has passed. A case whose advance is back at zero becomes
 * RETURNED. A case that is still open is included in one payroll recovery report and becomes SENT_TO_PAYROLL. That status
 * means the report was queued for payroll. It does not mean a salary deduction was made.
 *
 * <p>Email goes through the outbox. Nothing here sends mail directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceRecoveryService {

    static final String EMAIL_RECOVERY_NOTICE = "CASH_ADVANCE_RECOVERY_NOTICE";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceAdjustmentRepository adjustmentRepository;
    private final CashAdvanceRecoveryCaseRepository caseRepository;
    private final CashAdvanceBalanceLedger balanceLedger;
    private final EmailOutboxService emailOutbox;
    private final PolicySettingsService policy;
    private final UmsClient umsClient;
    private final CurrentUserService currentUserService;
    private final NotificationService notificationService;
    private final SalaryDeductionRequestRepository salaryDeductionRequestRepository;
    private final SalaryDeductionService salaryDeductions;

    // ---------------------------------------------------------------- route 2

    @Transactional
    public CashAdvanceRecoveryCase startRefundNotice(UUID advanceId) {
        requireFinance();
        String actor = currentUserService.getActorId();
        CashAdvance advance = cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found: " + advanceId));

        BigDecimal outstanding = outstanding(advance);
        if (outstanding.signum() <= 0 || "CLOSED".equalsIgnoreCase(advance.getStatus())) {
            throw new BusinessRuleViolationException("This advance has no outstanding balance to recover.");
        }
        if (caseRepository.findFirstByAdvance_AdvanceIdAndStatusIn(advanceId,
                List.of(CashAdvanceRecoveryCase.NOTICE_SENT, CashAdvanceRecoveryCase.SENT_TO_PAYROLL)).isPresent()) {
            throw new BusinessRuleViolationException("A recovery case is already open for this advance.");
        }

        UmsUserResponse employee = findEmployee(advance.getEmployeeId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "The employee was not found in the directory, so the refund notice cannot be sent."));
        if (employee.mail() == null || employee.mail().isBlank()) {
            throw new BusinessRuleViolationException("The employee has no email address, so the refund notice cannot be sent.");
        }

        LocalDateTime now = LocalDateTime.now();
        int graceDays = policy.graceDays();
        CashAdvanceRecoveryCase created = caseRepository.save(CashAdvanceRecoveryCase.builder()
                .advance(advance)
                .employeeId(advance.getEmployeeId())
                .amountAtNotice(outstanding)
                .status(CashAdvanceRecoveryCase.NOTICE_SENT)
                .noticeSentAt(now)
                .noticeSentBy(actor)
                .dueAt(BusinessDayCalculator.addBusinessDays(now, graceDays))
                .build());

        emailOutbox.enqueue(employee.mail(), "Refund of your cash advance is due",
                employeeNotice(employee, advance, outstanding, graceDays, created.getDueAt()),
                EMAIL_RECOVERY_NOTICE, created.getRecoveryCaseId().toString());

        // The same notice also appears in the in-app notification center.
        notificationService.notifyEmployee(advance.getEmployeeId(), new NotificationDraft(
                NotificationCategory.ACTION_REQUIRED,
                "CASH_ADVANCE_REFUND_NOTICE",
                "Refund of your cash advance is due",
                "Please return the outstanding amount by " + created.getDueAt().format(DATE)
                        + " (" + graceDays + " working day" + (graceDays == 1 ? "" : "s") + ").",
                null,
                null,
                null,
                outstanding,
                advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : null,
                "Refund due",
                "Return the amount",
                "/expense-management/cash-advance/" + advanceId));

        balanceLedger.recordChange("CashAdvanceRecoveryCase", created.getRecoveryCaseId(), "RECOVERY_NOTICE_SENT",
                null, "Status: NOTICE_SENT, Due: " + created.getDueAt(), actor,
                "Refund notice sent with a " + graceDays + "-day grace period");
        return created;
    }

    // ---------------------------------------------------------------- route 3

    /**
     * Runs on schedule. Settles cases whose advance is back at zero, and queues one payroll report for the open cases whose
     * grace period has ended. Returns the number of cases that moved to SENT_TO_PAYROLL.
     */
    @Transactional
    public int escalateDueCases(LocalDateTime now) {
        List<CashAdvanceRecoveryCase> due = caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, now);
        int initiated = 0;

        for (CashAdvanceRecoveryCase candidate : due) {
            // Lock order is advance first, then case. Repayments, payroll results and salary requests use the same order.
            UUID advanceId = candidate.getAdvance().getAdvanceId();
            CashAdvance advance = cashAdvanceRepository.findByIdForUpdate(advanceId).orElse(candidate.getAdvance());
            CashAdvanceRecoveryCase recoveryCase = caseRepository.findByIdForUpdate(candidate.getRecoveryCaseId()).orElse(null);
            if (recoveryCase == null || !CashAdvanceRecoveryCase.NOTICE_SENT.equals(recoveryCase.getStatus())) {
                continue;
            }
            BigDecimal outstanding = outstanding(advance);
            if (outstanding.signum() <= 0) {
                recoveryCase.setStatus(CashAdvanceRecoveryCase.RETURNED);
                recoveryCase.setResolvedAt(now);
                caseRepository.save(recoveryCase);
                balanceLedger.recordChange("CashAdvanceRecoveryCase", recoveryCase.getRecoveryCaseId(), "RECOVERY_RETURNED",
                        "Status: NOTICE_SENT", "Status: RETURNED", "system", "Balance returned within the grace period");
                continue;
            }
            if (hasOpenSalaryDeduction(advanceId)) {
                log.info("Recovery case {} left open: a salary deduction request already covers advance {}",
                        recoveryCase.getRecoveryCaseId(), advanceId);
                continue;
            }
            // Not paid by the due time: Finance's salary deduction request is created automatically for the outstanding amount.
            Optional<SalaryDeductionRequest> request = salaryDeductions.initiateForRecoveryCase(
                    advance, recoveryCase.getRecoveryCaseId(), outstanding, now.toLocalDate());
            if (request.isEmpty()) {
                log.warn("Recovery case {} stays open: no payroll email is configured for the salary deduction request",
                        recoveryCase.getRecoveryCaseId());
                continue;
            }
            recoveryCase.setStatus(CashAdvanceRecoveryCase.SENT_TO_PAYROLL);
            recoveryCase.setPayrollReportSentAt(now);
            caseRepository.save(recoveryCase);
            balanceLedger.recordChange("CashAdvanceRecoveryCase", recoveryCase.getRecoveryCaseId(), "RECOVERY_SALARY_DEDUCTION_INITIATED",
                    "Status: NOTICE_SENT", "Status: SENT_TO_PAYROLL, request: " + request.get().getPayrollRequestId(), "system",
                    "Payment not received by the due time; salary deduction requested automatically");
            initiated++;
        }
        return initiated;
    }

    /**
     * Closes open refund notices once the advance balance is zero. Called by the repayment path after it has locked
     * the advance, so the same lock order applies.
     */
    @Transactional
    public int resolveNoticesIfSettled(UUID advanceId, BigDecimal outstanding, LocalDateTime now) {
        if (outstanding.signum() > 0) {
            return 0;
        }
        int resolved = 0;
        for (CashAdvanceRecoveryCase candidate : caseRepository.findByAdvance_AdvanceIdAndStatusIn(
                advanceId, List.of(CashAdvanceRecoveryCase.NOTICE_SENT))) {
            CashAdvanceRecoveryCase recoveryCase = caseRepository.findByIdForUpdate(candidate.getRecoveryCaseId()).orElse(null);
            if (recoveryCase == null || !CashAdvanceRecoveryCase.NOTICE_SENT.equals(recoveryCase.getStatus())) {
                continue;
            }
            recoveryCase.setStatus(CashAdvanceRecoveryCase.RETURNED);
            recoveryCase.setResolvedAt(now);
            caseRepository.save(recoveryCase);
            balanceLedger.recordChange("CashAdvanceRecoveryCase", recoveryCase.getRecoveryCaseId(), "RECOVERY_RETURNED",
                    "Status: NOTICE_SENT", "Status: RETURNED", "system", "Repaid in full before the due time");
            resolved++;
        }
        return resolved;
    }

    // ---------------------------------------------------------------- resolution (Finance)

    /** Withdraws a refund notice that has not yet gone into a payroll report. The advance keeps its balance. */
    @Transactional
    public CashAdvanceRecoveryCase cancelNoticeCase(UUID recoveryCaseId, String reason) {
        requireFinance();
        String actor = currentUserService.getActorId();
        String note = blank(reason);
        if (note == null) {
            throw new BusinessRuleViolationException("Enter the reason for cancelling the refund notice.");
        }
        CashAdvanceRecoveryCase recoveryCase = caseRepository.findByIdForUpdate(recoveryCaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery case not found: " + recoveryCaseId));
        if (!CashAdvanceRecoveryCase.NOTICE_SENT.equals(recoveryCase.getStatus())) {
            throw new BusinessRuleViolationException("Only a refund notice that has not gone to payroll can be cancelled (current status: "
                    + recoveryCase.getStatus() + ").");
        }
        recoveryCase.setStatus(CashAdvanceRecoveryCase.CANCELLED);
        recoveryCase.setResolvedAt(LocalDateTime.now());
        recoveryCase.setResolutionNote(note);
        caseRepository.save(recoveryCase);
        balanceLedger.recordChange("CashAdvanceRecoveryCase", recoveryCase.getRecoveryCaseId(), "RECOVERY_NOTICE_CANCELLED",
                "Status: NOTICE_SENT", "Status: CANCELLED", actor, note);
        return recoveryCase;
    }

    private boolean hasOpenSalaryDeduction(UUID advanceId) {
        return salaryDeductionRequestRepository
                .findByAdvance_AdvanceIdAndStatusIn(advanceId, List.of(SalaryDeductionRequest.PENDING_SEND,
                        SalaryDeductionRequest.SENT_TO_PAYROLL, SalaryDeductionRequest.CANCELLED))
                .stream()
                .anyMatch(SalaryDeductionRequest::isOpen);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<CashAdvanceRecoveryCase> openCases() {
        requireFinance();
        return caseRepository.findByStatusIn(List.of(CashAdvanceRecoveryCase.NOTICE_SENT, CashAdvanceRecoveryCase.SENT_TO_PAYROLL));
    }

    // ---------------------------------------------------------------- text and lookups

    private String employeeNotice(UmsUserResponse employee, CashAdvance advance, BigDecimal outstanding, int graceDays,
                                  LocalDateTime dueAt) {
        return "Dear " + employee.firstName() + ",\n\n"
                + "Your cash advance has an outstanding amount of " + outstanding.toPlainString()
                + " " + (advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : "") + ".\n"
                + "Please return it by " + dueAt.format(DATE) + " (" + graceDays + " working day(s) from this notice).\n\n"
                + "If it is not returned by then, the amount will be recovered through the salary recovery process.\n";
    }

    private BigDecimal verifiedExpense(CashAdvance advance) {
        return adjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId()).stream()
                .map(a -> a.getAdjustedAmount() == null ? BigDecimal.ZERO : a.getAdjustedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Optional<UmsUserResponse> findEmployee(String employeeId) {
        if (employeeId == null) return Optional.empty();
        return umsClient.getAllUsers().stream()
                .filter(u -> u.userId() != null && String.valueOf(u.userId()).equals(employeeId.trim()))
                .findFirst();
    }

    private static BigDecimal outstanding(CashAdvance advance) {
        return advance.getOutstandingBalance() != null ? advance.getOutstandingBalance() : advance.getAmount();
    }

    private static void requireFinance() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean finance = auth != null && auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_FINANCE".equals(role) || "ROLE_FINANCE_EXECUTIVE".equals(role) || "ROLE_ADMIN".equals(role);
        });
        if (!finance) {
            throw new BusinessRuleViolationException("Only Finance can start a refund notice.");
        }
    }
}
