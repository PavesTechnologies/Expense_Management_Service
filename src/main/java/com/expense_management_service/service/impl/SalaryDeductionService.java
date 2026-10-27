package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.PayrollMonthOption;
import com.expense_management_service.dto.response.SalaryDeductionResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.entity.OutboundEmail;
import com.expense_management_service.entity.SalaryDeductionRequest;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.repository.CashAdvanceRecoveryCaseRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.SalaryDeductionRequestRepository;
import com.expense_management_service.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finance-initiated salary deduction. Finance requests an amount for a payroll month, the request email goes to the
 * payroll address through the outbox, and Payroll's result is recorded later. Only the actual amount Payroll reports
 * is posted to the advance balance.
 *
 * <p>Lock order everywhere: cash advance row first, then the salary deduction request row. Automated escalation
 * locks the recovery case and then the advance, which is consistent because nothing locks in the opposite order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalaryDeductionService {

    static final String EMAIL_REQUEST = "SALARY_DEDUCTION_REQUEST";
    static final String EMAIL_CANCELLATION = "SALARY_DEDUCTION_CANCELLATION";
    /** Recorded as the requester when the due-date run creates a request. */
    static final String SYSTEM_ACTOR = "SYSTEM";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Advances that have been disbursed and can still be recovered. APPROVED is excluded: nothing was paid out. */
    static final Set<String> RECOVERABLE_STATUSES = Set.of(
            "DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING", "SUBMITTED_FOR_REVIEW", "UNDER_REVIEW",
            "SETTLEMENT_PENDING", "EXPENSE_SUBMITTED", "EXPENSE_VERIFIED", "ADJUSTED", "PARTIALLY_SETTLED",
            "PARTIALLY_ADJUSTED", "OVERDUE");

    private static final List<String> OPEN_RECOVERY_STATUSES =
            List.of(CashAdvanceRecoveryCase.NOTICE_SENT, CashAdvanceRecoveryCase.SENT_TO_PAYROLL);

    private final CashAdvanceRepository cashAdvanceRepository;
    private final SalaryDeductionRequestRepository requestRepository;
    private final CashAdvanceRecoveryCaseRepository recoveryCaseRepository;
    private final CashAdvancePayrollPosting payrollPosting;
    private final PayrollMonthPolicy payrollMonths;
    private final EmailOutboxService emailOutbox;
    private final CashAdvanceBalanceLedger balanceLedger;
    private final PolicySettingsService policy;
    private final UmsClient umsClient;
    private final CurrentUserService currentUserService;

    /** The payroll component Payroll adds to the payslip for this recovery. Configurable; must exist in payroll. */
    @Value("${expense.payroll.deduction-component:Cash Advance Recovery}")
    private String deductionComponent = "Cash Advance Recovery";

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PayrollMonthOption> eligiblePayrollMonths() {
        requireFinance();
        return payrollMonths.eligibleMonths(LocalDate.now()).stream()
                .map(m -> new PayrollMonthOption(m.toString(), m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))))
                .toList();
    }

    /** Lists the advance's requests and refreshes each one's email state from the outbox. */
    @Transactional
    public List<SalaryDeductionResponse> listForAdvance(UUID advanceId) {
        requireFinance();
        CashAdvance advance = cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found: " + advanceId));
        List<SalaryDeductionResponse> out = new ArrayList<>();
        for (SalaryDeductionRequest request : requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)) {
            out.add(toResponse(syncEmail(request), advance));
        }
        return out;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public SalaryDeductionResponse create(UUID advanceId, BigDecimal amount, String payrollMonth) {
        String actor = requireFinance();
        LocalDate today = LocalDate.now();

        // Lock first so two submissions cannot both see "no open request" and both create one.
        CashAdvance advance = cashAdvanceRepository.findByIdForUpdate(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found: " + advanceId));

        String status = upper(advance.getStatus());
        if (!RECOVERABLE_STATUSES.contains(status)) {
            throw new BusinessRuleViolationException("Salary deduction is not available for an advance in status " + advance.getStatus() + ".");
        }
        BigDecimal balance = CashAdvancePayrollPosting.currentBalance(advance);
        if (balance.signum() <= 0) {
            throw new BusinessRuleViolationException("This advance has no outstanding balance to recover.");
        }
        int decimals = CashAdvancePayrollPosting.decimals(advance);
        requireAmount(amount, balance, decimals, false);
        YearMonth month = payrollMonths.requireEligible(payrollMonth, today);

        for (SalaryDeductionRequest existing : requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)) {
            if (existing.isOpen()) {
                throw new BusinessRuleViolationException("Request " + existing.getPayrollRequestId()
                        + " is still open for this advance. Wait for Payroll's result, or cancel it, before creating another.");
            }
        }
        if (recoveryCaseRepository.findFirstByAdvance_AdvanceIdAndStatusIn(advanceId, OPEN_RECOVERY_STATUSES).isPresent()) {
            throw new BusinessRuleViolationException("An automated refund notice or payroll recovery case is open for this advance. "
                    + "Resolve it first: cancel the notice, or record Payroll's result for the case.");
        }
        String payrollEmail = policy.payrollReportEmail();
        if (payrollEmail == null || payrollEmail.isBlank()) {
            throw new BusinessRuleViolationException("No payroll email is configured. Set the salary recovery report email in Settings first.");
        }

        SalaryDeductionRequest request = requestRepository.save(SalaryDeductionRequest.builder()
                .payrollRequestId(newPayrollRequestId(today))
                .advance(advance)
                .employeeId(advance.getEmployeeId())
                .requestedAmount(amount)
                .payrollMonth(month.toString())
                .status(SalaryDeductionRequest.PENDING_SEND)
                .requestedBy(actor)
                .requestedAt(LocalDateTime.now())
                .sentToPayroll(false)
                .outcomeRecorded(false)
                .build());

        OutboundEmail email = emailOutbox.enqueue(payrollEmail, requestSubject(request), requestBody(request, advance),
                EMAIL_REQUEST, request.getPayrollRequestId());
        request.setOutboundEmailId(email.getOutboundEmailId());
        requestRepository.save(request);

        balanceLedger.recordChange("SalaryDeductionRequest", request.getSalaryDeductionRequestId(), "SALARY_DEDUCTION_REQUESTED",
                null, "Status: PENDING_SEND, Amount: " + amount.toPlainString() + ", Payroll month: " + month,
                actor, "Requested " + amount.toPlainString() + " for payroll " + month + " (" + request.getPayrollRequestId() + ")");
        return toResponse(request, advance);
    }

    /**
     * Raised automatically when a payment request is not paid by its due time. The caller already holds the advance and
     * recovery case locks. Returns empty when no payroll address is configured, so the case stays open for the next run.
     */
    public Optional<SalaryDeductionRequest> initiateForRecoveryCase(CashAdvance advance, UUID recoveryCaseId,
                                                                    BigDecimal amount, LocalDate today) {
        String payrollEmail = policy.payrollReportEmail();
        if (payrollEmail == null || payrollEmail.isBlank() || amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        SalaryDeductionRequest request = requestRepository.save(SalaryDeductionRequest.builder()
                .payrollRequestId(newPayrollRequestId(today))
                .advance(advance)
                .employeeId(advance.getEmployeeId())
                .requestedAmount(amount)
                .payrollMonth(payrollMonths.firstEligibleMonth(today).toString())
                .status(SalaryDeductionRequest.PENDING_SEND)
                .requestedBy(SYSTEM_ACTOR)
                .requestedAt(LocalDateTime.now())
                .sentToPayroll(false)
                .outcomeRecorded(false)
                .recoveryCaseId(recoveryCaseId)
                .build());
        OutboundEmail email = emailOutbox.enqueue(payrollEmail, requestSubject(request), requestBody(request, advance),
                EMAIL_REQUEST, request.getPayrollRequestId());
        request.setOutboundEmailId(email.getOutboundEmailId());
        requestRepository.save(request);

        balanceLedger.recordChange("SalaryDeductionRequest", request.getSalaryDeductionRequestId(), "SALARY_DEDUCTION_REQUESTED",
                null, "Status: PENDING_SEND, Amount: " + amount.toPlainString() + ", Payroll month: " + request.getPayrollMonth(),
                SYSTEM_ACTOR, "Automatic: payment not received by the due time (recovery case " + recoveryCaseId + ")");
        return Optional.of(request);
    }

    // ------------------------------------------------------------------ email retry

    @Transactional
    public SalaryDeductionResponse retryEmail(UUID requestId) {
        String actor = requireFinance();
        SalaryDeductionRequest peek = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary deduction request not found: " + requestId));
        CashAdvance advance = cashAdvanceRepository.findByIdForUpdate(peek.getAdvance().getAdvanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found"));
        SalaryDeductionRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary deduction request not found: " + requestId));
        syncEmail(request);

        if (!SalaryDeductionRequest.PENDING_SEND.equals(request.getStatus())) {
            throw new BusinessRuleViolationException("Only a request that has not reached Payroll can have its email sent again.");
        }
        if (emailOutbox.hasLiveMessage(EMAIL_REQUEST, request.getPayrollRequestId())) {
            throw new BusinessRuleViolationException("The request email is already queued or delivered, so it is not sent twice.");
        }
        OutboundEmail email = emailOutbox.enqueue(policy.payrollReportEmail(), requestSubject(request),
                requestBody(request, advance), EMAIL_REQUEST, request.getPayrollRequestId());
        request.setOutboundEmailId(email.getOutboundEmailId());
        requestRepository.save(request);

        balanceLedger.recordChange("SalaryDeductionRequest", request.getSalaryDeductionRequestId(), "SALARY_DEDUCTION_EMAIL_RETRIED",
                null, "Email re-queued", actor, "Email retried for " + request.getPayrollRequestId());
        return toResponse(request, advance);
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public SalaryDeductionResponse cancel(UUID requestId, String reason) {
        String actor = requireFinance();
        String cleanReason = requireReason(reason);

        SalaryDeductionRequest peek = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary deduction request not found: " + requestId));
        CashAdvance advance = cashAdvanceRepository.findByIdForUpdate(peek.getAdvance().getAdvanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found"));
        SalaryDeductionRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary deduction request not found: " + requestId));
        syncEmail(request);

        String previousStatus = request.getStatus();
        if (SalaryDeductionRequest.PENDING_SEND.equals(previousStatus)) {
            // The request email has not been delivered. Withdraw it so the dispatcher never sends it.
            String emailStatus = request.getOutboundEmailId() == null ? OutboundEmail.CANCELLED
                    : emailOutbox.cancel(request.getOutboundEmailId());
            if (OutboundEmail.SENT.equals(emailStatus)) {
                // It was delivered between the sync and the cancel. Treat Payroll as having it.
                request.setSentToPayroll(true);
                request.setSentAt(LocalDateTime.now());
                sendCancellationNotice(request, advance, cleanReason);
            }
        } else if (SalaryDeductionRequest.SENT_TO_PAYROLL.equals(previousStatus)) {
            request.setSentToPayroll(true);
            sendCancellationNotice(request, advance, cleanReason);
        } else {
            throw new BusinessRuleViolationException("Request " + request.getPayrollRequestId()
                    + " is " + previousStatus + " and cannot be cancelled.");
        }

        request.setStatus(SalaryDeductionRequest.CANCELLED);
        request.setCancelledAt(LocalDateTime.now());
        request.setCancelledBy(actor);
        request.setCancellationReason(cleanReason);
        requestRepository.save(request);
        if (!request.isSentToPayroll()) {
            // Never reached Payroll: the recovery case is closed, so the due-date run does not raise it again by itself.
            closeLinkedRecoveryCase(request, actor, "Salary deduction " + request.getPayrollRequestId()
                    + " cancelled before delivery: " + cleanReason);
        }

        balanceLedger.recordChange("SalaryDeductionRequest", request.getSalaryDeductionRequestId(), "SALARY_DEDUCTION_CANCELLED",
                "Status: " + previousStatus, "Status: CANCELLED, sent to payroll: " + request.isSentToPayroll(),
                actor, cleanReason);
        return toResponse(request, advance);
    }

    // ------------------------------------------------------------------ Payroll result

    /**
     * Records what Payroll actually did. The actual amount is posted once, the request is marked recorded, and a repeat
     * call is refused. A zero result is recorded as PAYROLL_FAILED and leaves the balance unchanged.
     */
    @Transactional
    public SalaryDeductionResponse recordResult(UUID requestId, BigDecimal actualAmount, String payrollMonthProcessed,
                                                String payrollReference, String evidenceType, String evidenceNote,
                                                MultipartFile evidenceFile) {
        String actor = requireFinance();
        SalaryDeductionRequest peek = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary deduction request not found: " + requestId));
        CashAdvance advance = cashAdvanceRepository.findByIdForUpdate(peek.getAdvance().getAdvanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found"));
        SalaryDeductionRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary deduction request not found: " + requestId));
        syncEmail(request);

        if (request.isOutcomeRecorded()) {
            throw new BusinessRuleViolationException("The Payroll result for " + request.getPayrollRequestId()
                    + " is already recorded. It cannot be recorded twice.");
        }
        String status = request.getStatus();
        boolean cancelledAfterSend = SalaryDeductionRequest.CANCELLED.equals(status) && request.isSentToPayroll();
        if (!SalaryDeductionRequest.SENT_TO_PAYROLL.equals(status) && !cancelledAfterSend) {
            throw new BusinessRuleViolationException("Payroll's result can be recorded only after the request has reached Payroll (current status: "
                    + status + ").");
        }

        BigDecimal balance = CashAdvancePayrollPosting.currentBalance(advance);
        requireAmount(actualAmount, balance, CashAdvancePayrollPosting.decimals(advance), true);
        if (actualAmount.compareTo(request.getRequestedAmount()) > 0) {
            throw new BusinessRuleViolationException("The actual deduction cannot exceed the requested amount ("
                    + request.getRequestedAmount().toPlainString() + ").");
        }
        if (payrollMonthProcessed == null || !request.getPayrollMonth().equals(payrollMonthProcessed.trim())) {
            throw new BusinessRuleViolationException("The payroll month processed must match the requested month ("
                    + request.getPayrollMonth() + ").");
        }

        String reference = blank(payrollReference);
        boolean hasFile = evidenceFile != null && !evidenceFile.isEmpty();
        String note = blank(evidenceNote);
        CashAdvancePayrollPosting.requireReferenceOrEvidence(reference, evidenceType, hasFile, note);
        String evidenceKey = hasFile ? payrollPosting.storeEvidence(advance.getAdvanceId(), evidenceFile) : null;

        if (actualAmount.signum() > 0) {
            payrollPosting.post(advance, actualAmount, reference, evidenceKey, note, actor,
                    "Payroll deduction for " + request.getPayrollRequestId());
        }

        request.setActualAmountDeducted(actualAmount);
        request.setPayrollMonthProcessed(payrollMonthProcessed.trim());
        request.setPayrollReference(reference);
        request.setEvidenceType(reference == null ? evidenceType : blank(evidenceType));
        request.setEvidenceReference(evidenceKey);
        request.setEvidenceNote(note);
        request.setConfirmedAt(LocalDateTime.now());
        request.setConfirmedBy(actor);
        request.setOutcomeRecorded(true);
        if (!cancelledAfterSend) {
            request.setStatus(actualAmount.signum() == 0 ? SalaryDeductionRequest.PAYROLL_FAILED : SalaryDeductionRequest.PAYROLL_CONFIRMED);
        }
        requestRepository.save(request);

        if (request.getRecoveryCaseId() != null) {
            // Any result, including zero, ends the automatic recovery. A further request is raised by Finance.
            recoveryCaseRepository.findByIdForUpdate(request.getRecoveryCaseId())
                    .filter(c -> CashAdvanceRecoveryCase.SENT_TO_PAYROLL.equals(c.getStatus()))
                    .ifPresent(c -> {
                        c.setStatus(CashAdvanceRecoveryCase.PAYROLL_RECORDED);
                        c.setPayrollActualDeducted(actualAmount);
                        c.setPayrollReference(reference);
                        c.setPayrollRecordedAt(LocalDateTime.now());
                        c.setPayrollRecordedBy(actor);
                        c.setResolvedAt(LocalDateTime.now());
                        c.setResolutionNote("Salary deduction " + request.getPayrollRequestId() + ": " + request.getStatus());
                        recoveryCaseRepository.save(c);
                        balanceLedger.recordChange("CashAdvanceRecoveryCase", c.getRecoveryCaseId(), "RECOVERY_PAYROLL_RESULT_RECORDED",
                                "Status: SENT_TO_PAYROLL", "Status: PAYROLL_RECORDED, actual: " + actualAmount.toPlainString(),
                                actor, "Payroll result for " + request.getPayrollRequestId());
                    });
        }

        BigDecimal shortfall = request.getRequestedAmount().subtract(actualAmount);
        balanceLedger.recordChange("SalaryDeductionRequest", request.getSalaryDeductionRequestId(), "SALARY_DEDUCTION_RESULT_RECORDED",
                "Status: " + status, "Status: " + request.getStatus() + ", actual: " + actualAmount.toPlainString()
                        + ", shortfall: " + shortfall.toPlainString(),
                actor, "Payroll result for " + request.getPayrollRequestId());
        return toResponse(request, advance);
    }

    // ------------------------------------------------------------------ helpers

    /** Moves a PENDING_SEND request to SENT_TO_PAYROLL once its email has been delivered. Never the reverse. */
    private SalaryDeductionRequest syncEmail(SalaryDeductionRequest request) {
        if (!SalaryDeductionRequest.PENDING_SEND.equals(request.getStatus()) || request.getOutboundEmailId() == null) {
            return request;
        }
        Optional<OutboundEmail> email = emailOutbox.find(request.getOutboundEmailId());
        if (email.isPresent() && OutboundEmail.SENT.equals(email.get().getStatus())) {
            request.setStatus(SalaryDeductionRequest.SENT_TO_PAYROLL);
            request.setSentToPayroll(true);
            request.setSentAt(email.get().getSentAt());
            requestRepository.save(request);
        }
        return request;
    }

    private void closeLinkedRecoveryCase(SalaryDeductionRequest request, String actor, String note) {
        if (request.getRecoveryCaseId() == null) {
            return;
        }
        recoveryCaseRepository.findByIdForUpdate(request.getRecoveryCaseId())
                .filter(c -> CashAdvanceRecoveryCase.SENT_TO_PAYROLL.equals(c.getStatus()))
                .ifPresent(c -> {
                    c.setStatus(CashAdvanceRecoveryCase.CANCELLED);
                    c.setResolvedAt(LocalDateTime.now());
                    c.setResolutionNote(note);
                    recoveryCaseRepository.save(c);
                    balanceLedger.recordChange("CashAdvanceRecoveryCase", c.getRecoveryCaseId(), "RECOVERY_CANCELLED_WITH_REQUEST",
                            "Status: SENT_TO_PAYROLL", "Status: CANCELLED", actor, note);
                });
    }

    private void sendCancellationNotice(SalaryDeductionRequest request, CashAdvance advance, String reason) {
        emailOutbox.enqueue(policy.payrollReportEmail(),
                "Salary deduction cancelled - " + request.getPayrollRequestId(),
                "Finance has cancelled salary deduction request " + request.getPayrollRequestId() + " for employee "
                        + request.getEmployeeId() + " (advance " + advance.getAdvanceId() + ").\n\n"
                        + "Requested amount: " + request.getRequestedAmount().toPlainString() + " for payroll month "
                        + request.getPayrollMonth() + ".\n"
                        + "Reason: " + reason + "\n\n"
                        + "This request was already sent to Payroll. If the deduction has been processed, reply with the amount "
                        + "actually deducted and the payroll month, so Finance can record it.\n",
                EMAIL_CANCELLATION, request.getPayrollRequestId());
    }

    private String requestSubject(SalaryDeductionRequest request) {
        return SalaryDeductionEmailTemplate.requestSubject(request, employeeName(request.getEmployeeId()));
    }

    /** The body Payroll works from. Built from stored data only; nothing here is an authorization ID. */
    private String requestBody(SalaryDeductionRequest request, CashAdvance advance) {
        return SalaryDeductionEmailTemplate.requestBody(request, advance, employeeName(request.getEmployeeId()),
                CashAdvancePayrollPosting.currentBalance(advance), deductionComponent);
    }

    private String employeeName(String employeeId) {
        if (employeeId == null) return "-";
        return umsClient.getAllUsers().stream()
                .filter(u -> u.userId() != null && String.valueOf(u.userId()).equals(employeeId.trim()))
                .findFirst()
                .map(u -> (u.firstName() + " " + (u.lastName() == null ? "" : u.lastName())).trim())
                .orElse(employeeId);
    }

    /** Amount checks shared by create and result. Zero is allowed only for a recorded result. */
    static void requireAmount(BigDecimal amount, BigDecimal balance, int decimals, boolean allowZero) {
        if (amount == null) {
            throw new IllegalArgumentException("Enter the amount.");
        }
        if (allowZero ? amount.signum() < 0 : amount.signum() <= 0) {
            throw new IllegalArgumentException(allowZero ? "The amount cannot be negative." : "Enter an amount greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > decimals) {
            throw new IllegalArgumentException("The amount can have at most " + decimals + " decimal places.");
        }
        if (amount.compareTo(balance) > 0) {
            throw new BusinessRuleViolationException("The amount (" + amount.toPlainString()
                    + ") cannot exceed the outstanding balance (" + balance.toPlainString() + ").");
        }
    }

    /** PR-&lt;financial year&gt;-&lt;8 hex characters&gt;, for example PR-2026-2027-1A2B3C4D. Not an authorization ID. */
    static String newPayrollRequestId(LocalDate today) {
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return "PR-" + startYear + "-" + (startYear + 1) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    SalaryDeductionResponse toResponse(SalaryDeductionRequest request, CashAdvance advance) {
        boolean sentToPayroll = request.isSentToPayroll();
        boolean cancelledAfterSend = SalaryDeductionRequest.CANCELLED.equals(request.getStatus()) && sentToPayroll;
        boolean pendingSend = SalaryDeductionRequest.PENDING_SEND.equals(request.getStatus());
        String emailStatus = emailStatus(request);
        BigDecimal actual = request.getActualAmountDeducted();
        return new SalaryDeductionResponse(
                request.getSalaryDeductionRequestId(),
                request.getPayrollRequestId(),
                advance.getAdvanceId(),
                request.getEmployeeId(),
                request.getRequestedAmount(),
                request.getPayrollMonth(),
                request.getStatus(),
                request.isOpen(),
                emailStatus,
                request.getRequestedAt(),
                request.getRequestedBy(),
                request.getSentAt(),
                sentToPayroll,
                request.getCancelledAt(),
                request.getCancelledBy(),
                request.getCancellationReason(),
                request.isOutcomeRecorded(),
                actual,
                actual == null ? null : request.getRequestedAmount().subtract(actual),
                request.getPayrollMonthProcessed(),
                request.getPayrollReference(),
                request.getEvidenceType(),
                request.getEvidenceReference() != null,
                request.getEvidenceNote(),
                request.getConfirmedAt(),
                request.getConfirmedBy(),
                CashAdvancePayrollPosting.currentBalance(advance),
                pendingSend && "FAILED".equals(emailStatus),
                pendingSend || SalaryDeductionRequest.SENT_TO_PAYROLL.equals(request.getStatus()),
                (SalaryDeductionRequest.SENT_TO_PAYROLL.equals(request.getStatus()) || cancelledAfterSend)
                        && !request.isOutcomeRecorded());
    }

    private String emailStatus(SalaryDeductionRequest request) {
        if (request.getOutboundEmailId() == null) return "NOT_QUEUED";
        return emailOutbox.find(request.getOutboundEmailId())
                .map(OutboundEmail::getStatus)
                .orElse("NOT_QUEUED");
    }

    /** Finance only. Matches the recovery service's guard, which also admits Finance Executive and Admin. */
    String requireFinance() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean finance = auth != null && auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_FINANCE".equals(role) || "ROLE_FINANCE_EXECUTIVE".equals(role) || "ROLE_ADMIN".equals(role);
        });
        if (!finance) {
            throw new BusinessRuleViolationException("Only Finance can manage salary deductions.");
        }
        return currentUserService.getActorId();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireReason(String reason) {
        String cleaned = blank(reason);
        if (cleaned == null) {
            throw new BusinessRuleViolationException("Enter the reason for cancelling the request.");
        }
        if (cleaned.length() > 500) {
            throw new BusinessRuleViolationException("The cancellation reason must be 500 characters or fewer.");
        }
        return cleaned;
    }
}
