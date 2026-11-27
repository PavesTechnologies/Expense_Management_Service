package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SupplementaryClaimCreateRequest;
import com.expense_management_service.dto.request.SupplementaryClaimItemRequest;
import com.expense_management_service.dto.response.SupplementaryClaimHistoryResponse;
import com.expense_management_service.dto.response.SupplementaryClaimItemResponse;
import com.expense_management_service.dto.response.SupplementaryClaimResponse;
import com.expense_management_service.dto.response.SupplementaryEligibilityResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SalaryDeductionRequest;
import com.expense_management_service.entity.SupplementaryClaimHistory;
import com.expense_management_service.entity.SupplementaryClaimItem;
import com.expense_management_service.entity.SupplementaryExpenseClaim;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceRecoveryCaseRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.repository.SalaryDeductionRequestRepository;
import com.expense_management_service.repository.SupplementaryClaimHistoryRepository;
import com.expense_management_service.repository.SupplementaryExpenseClaimRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.PermissionConstants;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import com.expense_management_service.service.SupplementaryClaimService;
import com.expense_management_service.service.SupplementaryPaymentService;
import com.expense_management_service.service.SupplementaryReceiptService;
import com.expense_management_service.dto.response.SupplementaryReceiptResponse;
import com.expense_management_service.entity.Receipt;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional
public class SupplementaryClaimServiceImpl implements SupplementaryClaimService {

    /** Reasons an employee can give for a missing expense. OTHER needs a written explanation. */
    static final Set<String> LATE_REASON_CODES = Set.of(
            "RECEIPT_RECEIVED_LATE", "FORGOT_TO_SUBMIT", "NOT_AVAILABLE_IN_REPORT", "OTHER");
    static final String REASON_OTHER = "OTHER";

    /** Claims that still hold a place for this report: no second claim may start until they finish. */
    static final Set<String> IN_FLIGHT = Set.of(
            SupplementaryExpenseClaim.DRAFT,
            SupplementaryExpenseClaim.PENDING_MANAGER,
            SupplementaryExpenseClaim.PENDING_FINANCE,
            SupplementaryExpenseClaim.CLARIFICATION_REQUIRED,
            SupplementaryExpenseClaim.APPROVED,
            SupplementaryExpenseClaim.PAYMENT_PENDING);

    /** Every status a claim can hold, used to list all claims raised against one report. */
    static final Set<String> ALL_STATUSES = Set.of(
            SupplementaryExpenseClaim.DRAFT, SupplementaryExpenseClaim.PENDING_MANAGER,
            SupplementaryExpenseClaim.MANAGER_REJECTED, SupplementaryExpenseClaim.PENDING_FINANCE,
            SupplementaryExpenseClaim.FINANCE_REJECTED, SupplementaryExpenseClaim.CLARIFICATION_REQUIRED,
            SupplementaryExpenseClaim.APPROVED, SupplementaryExpenseClaim.PAYMENT_PENDING,
            SupplementaryExpenseClaim.SETTLED, SupplementaryExpenseClaim.CANCELLED);

    /** Claims whose items and reason may still change. */
    static final Set<String> EDITABLE = Set.of(
            SupplementaryExpenseClaim.DRAFT,
            SupplementaryExpenseClaim.CLARIFICATION_REQUIRED);

    /**
     * Report statuses a supplementary claim can be raised against. A paid report can still show APPROVED, because AP
     * payment changes paymentRoutingStatus and not reportStatus. APPROVED is therefore allowed here, and whether the
     * report is really paid is decided by its payment fields and cash advance state in {@link SupplementaryEligibility}.
     */
    static final Set<ReportStatus> SETTLED_REPORT_STATUSES =
            EnumSet.of(ReportStatus.APPROVED, ReportStatus.REIMBURSED, ReportStatus.CLOSED);

    /** Every salary deduction status, so each one is checked against the blocking rule. */
    static final List<String> SALARY_STATUSES = List.of(
            SalaryDeductionRequest.PENDING_SEND,
            SalaryDeductionRequest.SENT_TO_PAYROLL,
            SalaryDeductionRequest.PAYROLL_CONFIRMED,
            SalaryDeductionRequest.PAYROLL_FAILED,
            SalaryDeductionRequest.CANCELLED);

    static final int MIN_DECISION_COMMENT = 5;
    private static final DateTimeFormatter CLAIM_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SupplementaryExpenseClaimRepository claimRepository;
    private final SupplementaryClaimHistoryRepository historyRepository;
    private final ExpenseReportRepository reportRepository;
    private final ExpenseCategoryRepository categoryRepository;
    private final CashAdvanceRecoveryCaseRepository recoveryRepository;
    private final CashAdvanceCarryForwardRepository carryForwardRepository;
    private final SalaryDeductionRequestRepository salaryDeductionRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;
    private final ReceiptRepository receiptRepository;
    private final SupplementaryPaymentService paymentService;
    private final SupplementaryReceiptService receiptService;
    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceBalanceCalculator balanceCalculator;
    private final CashAdvanceBalanceLedger cashAdvanceBalanceLedger;

    // ---------------------------------------------------------------- eligibility

    @Override
    @Transactional(readOnly = true)
    public SupplementaryEligibilityResponse checkEligibility(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        Eligibility result = evaluate(report);
        AdvanceTarget target = result.eligible() ? settlementTarget(report.getCashAdvance()) : null;
        return new SupplementaryEligibilityResponse(report.getReportId(), report.getReportNumber(), result.eligible(),
                result.reason(), result.amountOwed(), result.hasCashAdvance(),
                target == null ? null : target.advance().getAdvanceId(),
                target == null ? null : target.advance().getTitle(),
                target == null ? null : target.remaining());
    }

    /**
     * Whole-report decision, using the shared amount calculation and the settlement predicates. Missing or unknown states
     * count as not eligible.
     */
    Eligibility evaluate(ExpenseReport report) {
        if (report.getReportStatus() == null || !SETTLED_REPORT_STATUSES.contains(report.getReportStatus())) {
            return new Eligibility(false,
                    "The report must be approved and paid before a supplementary claim can be raised.",
                    SettlementAmounts.amountOwed(report), report.getCashAdvance() != null);
        }
        BigDecimal owed = SettlementAmounts.amountOwed(report);
        boolean hasAdvance = report.getCashAdvance() != null;
        String routing = report.getPaymentRoutingStatus() == null ? null : report.getPaymentRoutingStatus().name();
        boolean apSettled = SupplementaryEligibility.apSettled(routing, report.getPaymentCompletedAt() != null,
                report.getPaidAmount(), owed);

        boolean advanceReconciled = false;
        if (hasAdvance) {
            CashAdvance advance = report.getCashAdvance();
            boolean open = hasOpenAdvanceItems(advance.getAdvanceId());
            advanceReconciled = SupplementaryEligibility.advanceReconciled(advance.getStatus(),
                    advance.getSettledAt() != null, open);
        }
        boolean eligible = SupplementaryEligibility.reportEligible(owed, hasAdvance, advanceReconciled, apSettled);
        String reason = eligible ? null : explain(hasAdvance, advanceReconciled, owed, apSettled);
        return new Eligibility(eligible, reason, owed, hasAdvance);
    }

    private String explain(boolean hasAdvance, boolean advanceReconciled, BigDecimal owed, boolean apSettled) {
        if (!hasAdvance) {
            return "The report's payment through AP is not complete yet.";
        }
        if (!advanceReconciled) {
            return "The linked cash advance is not fully reconciled yet, or has an open recovery, deduction or carry-forward.";
        }
        if (owed == null || owed.signum() != 0) {
            return "The balance owed on the report is not yet paid through AP.";
        }
        return "The report is not eligible for a supplementary claim.";
    }

    /** True when any salary deduction, recovery case or carry-forward still holds the advance open. */
    private boolean hasOpenAdvanceItems(UUID advanceId) {
        boolean salaryOpen = salaryDeductionRepository.findByAdvance_AdvanceIdAndStatusIn(advanceId, SALARY_STATUSES).stream()
                .anyMatch(d -> SupplementaryEligibility.salaryDeductionBlocks(d.isOpen(), d.getStatus()));
        boolean recoveryOpen = !recoveryRepository
                .findByAdvance_AdvanceIdAndStatusIn(advanceId, SupplementaryEligibility.RECOVERY_OPEN).isEmpty();
        boolean carryOpen = !carryForwardRepository
                .findBySourceAdvance_AdvanceIdAndStatusIn(advanceId, SupplementaryEligibility.CARRY_FORWARD_BLOCKING).isEmpty()
                || !carryForwardRepository
                .findByDestinationAdvance_AdvanceIdAndStatusIn(advanceId, SupplementaryEligibility.CARRY_FORWARD_BLOCKING).isEmpty();
        return salaryOpen || recoveryOpen || carryOpen;
    }

    record Eligibility(boolean eligible, String reason, BigDecimal amountOwed, boolean hasCashAdvance) {
    }

    // ---------------------------------------------------------------- carried balance: where a claim is settled

    private static final Set<String> ADVANCE_DONE = Set.of("CLOSED", "SETTLED", "CANCELLED", "REJECTED");
    private static final int MAX_CARRY_HOPS = 5;

    /** The open advance that holds the original advance's carried balance, and what it still holds. */
    record AdvanceTarget(CashAdvance advance, BigDecimal remaining) {
    }

    /**
     * Follows the original advance's applied carry-forwards (newest first, through closed advances) to the first open
     * advance. Null, so AP pays, when the balance was not carried forward, every advance it reached is closed, or the
     * open advance is already in use (a report on it has gone past draft): its figures are not changed while its own
     * reports are being approved, the same as after a salary deduction.
     */
    AdvanceTarget settlementTarget(CashAdvance original) {
        if (original == null || carryForwardRepository == null || balanceCalculator == null) {
            return null;
        }
        CashAdvance current = original;
        Set<UUID> seen = new java.util.HashSet<>();
        for (int hop = 0; hop < MAX_CARRY_HOPS && current != null && seen.add(current.getAdvanceId()); hop++) {
            CashAdvance next = carryForwardRepository
                    .findBySourceAdvance_AdvanceIdAndStatusIn(current.getAdvanceId(), Set.of(CashAdvanceCarryForward.APPLIED))
                    .stream()
                    .max(Comparator.comparing(cf -> cf.getAppliedAt() == null ? LocalDateTime.MIN : cf.getAppliedAt()))
                    .map(CashAdvanceCarryForward::getDestinationAdvance)
                    .orElse(null);
            if (next == null) {
                return null;
            }
            String status = next.getStatus() == null ? "" : next.getStatus().toUpperCase(Locale.ROOT);
            if (!ADVANCE_DONE.contains(status)) {
                if (inUse(next)) {
                    return null;
                }
                BigDecimal owed = balanceCalculator.rawOutstandingFor(List.of(next)).getOrDefault(next.getAdvanceId(), BigDecimal.ZERO);
                return new AdvanceTarget(next, owed == null ? BigDecimal.ZERO : owed.max(BigDecimal.ZERO));
            }
            current = next;
        }
        return null;
    }

    /**
     * On Finance approval: up to what the target advance still holds is set against it (an offset linked to the original
     * report, marked as a supplementary claim offset, so the report's own figures do not change); the rest goes to AP.
     * When everything is set against the advance, the claim is settled at once and no payment is requested.
     */
    private SupplementaryExpenseClaim settleAgainstAdvance(SupplementaryExpenseClaim claim) {
        BigDecimal total = claim.getTotalAmount() == null ? BigDecimal.ZERO : claim.getTotalAmount();
        AdvanceTarget target = cashAdvanceAdjustmentRepository == null ? null : settlementTarget(claim.getOriginal().getCashAdvance());
        BigDecimal offset = target == null ? BigDecimal.ZERO : total.min(target.remaining()).max(BigDecimal.ZERO);
        if (offset.signum() <= 0) {
            paymentService.requestPayment(claim);
            return claim;
        }
        CashAdvance advance = target.advance();
        String actor = currentUserService.getActorId();
        LocalDateTime now = LocalDateTime.now();
        ExpenseReport report = claim.getOriginal();
        // One active offset per advance and report (database unique key): a second claim on the same report adds to it.
        CashAdvanceAdjustment existing = cashAdvanceAdjustmentRepository.findByReport_ReportId(report.getReportId()).stream()
                .filter(CashAdvanceAdjustment::isActive)
                .filter(a -> a.getCashAdvance() != null && advance.getAdvanceId().equals(a.getCashAdvance().getAdvanceId()))
                .findFirst().orElse(null);
        if (existing != null && existing.countsForReport()) {
            // The report already has a regular offset on this advance; it must stay the report's own. Pay by AP instead.
            paymentService.requestPayment(claim);
            return claim;
        }
        if (existing != null) {
            BigDecimal before = existing.getAdjustedAmount();
            existing.setAdjustedAmount(before.add(offset));
            existing.setTransactionReference(trimTo(existing.getTransactionReference() + "," + claim.getClaimNumber(), 255));
            cashAdvanceAdjustmentRepository.save(existing);
        } else {
            cashAdvanceAdjustmentRepository.save(CashAdvanceAdjustment.builder()
                    .cashAdvance(advance)
                    .report(report)
                    .adjustedAmount(offset)
                    .adjustedBy(actor)
                    .adjustedAt(now)
                    .paymentMethod(CashAdvanceAdjustment.SUPPLEMENTARY_CLAIM)
                    .transactionReference(claim.getClaimNumber())
                    .idempotencyKey("supp-offset-" + claim.getClaimId())
                    .build());
        }
        BigDecimal remainingAfter = target.remaining().subtract(offset).max(BigDecimal.ZERO);
        cashAdvanceBalanceLedger.applyBalance(advance, remainingAfter, "SUPPLEMENTARY_CLAIM_OFFSET", actor,
                "Late bill " + claim.getClaimNumber() + " (report " + report.getReportNumber() + ") set against this advance for " + offset);
        cashAdvanceBalanceLedger.refreshStatus(advance, actor, "Rechecked after supplementary claim " + claim.getClaimNumber());
        cashAdvanceRepository.save(advance);

        claim.setSettledAdvanceId(advance.getAdvanceId());
        claim.setAdvanceOffsetAmount(offset);
        BigDecimal rest = total.subtract(offset);
        String note = offset + " set against advance \"" + advance.getTitle() + "\" (carried-forward balance)";
        if (rest.signum() > 0) {
            SupplementaryExpenseClaim saved = claimRepository.save(claim);
            record(saved, "SET_AGAINST_ADVANCE", SupplementaryExpenseClaim.PAYMENT_PENDING, SupplementaryExpenseClaim.PAYMENT_PENDING,
                    note + "; " + rest + " to be paid by AP", "FINANCE");
            paymentService.requestPayment(saved, rest);
            return saved;
        }
        claim.setStatus(SupplementaryExpenseClaim.SETTLED);
        claim.setSettledAt(now);
        claim.setUpdatedAt(now);
        SupplementaryExpenseClaim saved = claimRepository.save(claim);
        record(saved, "SETTLED_AGAINST_ADVANCE", SupplementaryExpenseClaim.PAYMENT_PENDING, SupplementaryExpenseClaim.SETTLED, note, "FINANCE");
        return saved;
    }

    /** Reports that do not put an advance in use: not yet submitted, or cancelled. */
    private static final Set<ReportStatus> NOT_USING_ADVANCE = EnumSet.of(ReportStatus.DRAFT, ReportStatus.CANCELLED);

    /** True when any expense report on the advance has gone past draft (submitted, in approval, approved or paid). */
    private boolean inUse(CashAdvance advance) {
        if (reportRepository == null) {
            return true;
        }
        return reportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId()).stream()
                .anyMatch(r -> r.getReportStatus() != null && !NOT_USING_ADVANCE.contains(r.getReportStatus()));
    }

    private static String trimTo(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------------------------------------------------------- create

    @Override
    public SupplementaryClaimResponse createForSelf(SupplementaryClaimCreateRequest request) {
        String actor = requireEmployeeId();
        ExpenseReport report = findReport(request.reportId());
        if (!actor.equals(report.getEmployeeId())) {
            throw new AccessDeniedException("You can raise a supplementary claim only for your own report.");
        }
        SupplementaryExpenseClaim claim = createDraft(report, actor, false, null, request);
        return toResponse(claim, true);
    }

    @Override
    public SupplementaryClaimResponse createOnBehalf(SupplementaryClaimCreateRequest request) {
        String actor = requireEmployeeId();
        requirePermission(PermissionConstants.SUPP_CLAIM_CREATE_FOR_EMPLOYEE);
        if (request.employeeId() == null || request.employeeId().isBlank()) {
            throw new BusinessRuleViolationException("Name the employee this claim is for.");
        }
        if (request.creationReason() == null || request.creationReason().isBlank()) {
            throw new BusinessRuleViolationException("Give a reason for creating this claim on the employee's behalf.");
        }
        ExpenseReport report = findReport(request.reportId());
        if (!request.employeeId().equals(report.getEmployeeId())) {
            throw new BusinessRuleViolationException("The report does not belong to the employee named on the claim.");
        }
        SupplementaryExpenseClaim claim = createDraft(report, actor, true, request.creationReason().trim(), request);
        notifyEmployee(claim, "SUPP_CLAIM_ACKNOWLEDGE", "Supplementary claim needs your acknowledgment",
                "Finance created a supplementary claim for you. Review it and acknowledge it so it can be submitted. "
                        + "Acknowledging does not approve the claim.", NotificationCategory.ACTION_REQUIRED);
        return toResponse(claim, true);
    }

    private SupplementaryExpenseClaim createDraft(ExpenseReport report, String actor, boolean onBehalf, String creationReason,
                                                  SupplementaryClaimCreateRequest request) {
        Eligibility eligibility = evaluate(report);
        if (!eligibility.eligible()) {
            throw new BusinessRuleViolationException(eligibility.reason());
        }
        if (claimRepository.existsByOriginal_ReportIdAndStatusIn(report.getReportId(), IN_FLIGHT)) {
            throw new BusinessRuleViolationException("This report already has a supplementary claim in progress.");
        }
        String reasonCode = request.lateReasonCode() == null ? "" : request.lateReasonCode().trim().toUpperCase(Locale.ROOT);
        if (!LATE_REASON_CODES.contains(reasonCode)) {
            throw new BusinessRuleViolationException("Choose a valid reason for the missing expense.");
        }
        String reasonText = trimmedOrNull(request.lateReasonText());
        if (REASON_OTHER.equals(reasonCode) && (reasonText == null || reasonText.length() < MIN_DECISION_COMMENT)) {
            throw new BusinessRuleViolationException("Explain the reason in at least " + MIN_DECISION_COMMENT + " characters.");
        }
        LocalDateTime now = LocalDateTime.now();
        SupplementaryExpenseClaim claim = SupplementaryExpenseClaim.builder()
                .original(report)
                .claimNumber(newClaimNumber())
                .employeeId(report.getEmployeeId())
                .createdBy(actor)
                .createdOnBehalf(onBehalf)
                .creationReason(creationReason)
                .lateReasonCode(reasonCode)
                .lateReasonText(reasonText)
                .totalAmount(BigDecimal.ZERO.setScale(4))
                .currencyCode(report.getCurrency().getCurrencyCode())
                .originalCashAdvanceId(report.getCashAdvance() == null ? null : report.getCashAdvance().getAdvanceId())
                .status(SupplementaryExpenseClaim.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .items(new ArrayList<>())
                .build();
        if (request.items() != null) {
            for (SupplementaryClaimItemRequest item : request.items()) {
                addItemTo(claim, report, item);
            }
        }
        recalculateTotal(claim);
        SupplementaryExpenseClaim saved = claimRepository.save(claim);
        record(saved, "CREATED", null, SupplementaryExpenseClaim.DRAFT, reasonText, onBehalf ? "FINANCE" : "EMPLOYEE");
        return saved;
    }

    // ---------------------------------------------------------------- items

    @Override
    public SupplementaryClaimResponse addItem(UUID claimId, SupplementaryClaimItemRequest request) {
        SupplementaryExpenseClaim claim = findClaimForEditing(claimId);
        addItemTo(claim, claim.getOriginal(), request);
        recalculateTotal(claim);
        return toResponse(claimRepository.save(claim), true);
    }

    @Override
    public SupplementaryClaimResponse addItemFromReceipt(UUID claimId, UUID receiptId, SupplementaryClaimItemRequest request) {
        SupplementaryExpenseClaim claim = findClaimForEditing(claimId);
        Receipt receipt = receiptRepository.findByIdForUpdate(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found."));
        if (!Receipt.ATTACHMENT_SUPPLEMENTARY.equals(receipt.getAttachmentKind())) {
            throw new BusinessRuleViolationException("Only a receipt uploaded for this claim can fill an expense.");
        }
        if (!claim.getEmployeeId().equals(receipt.getEmployeeId())) {
            throw new AccessDeniedException("This receipt belongs to another employee.");
        }
        if (receipt.getSupplementaryClaimItem() != null) {
            throw new BusinessRuleViolationException("This receipt is already attached to an expense.");
        }
        addItemTo(claim, claim.getOriginal(), request);
        recalculateTotal(claim);
        SupplementaryExpenseClaim saved = claimRepository.save(claim);
        // The expense added last is the one just created; it now carries the receipt.
        SupplementaryClaimItem created = saved.getItems().get(saved.getItems().size() - 1);
        receipt.setSupplementaryClaimItem(created);
        receiptRepository.save(receipt);
        return toResponse(saved, true);
    }

    @Override
    public SupplementaryClaimResponse updateItem(UUID claimId, UUID itemId, SupplementaryClaimItemRequest request) {
        SupplementaryExpenseClaim claim = findClaimForEditing(claimId);
        SupplementaryClaimItem item = claim.getItems().stream()
                .filter(i -> itemId.equals(i.getClaimItemId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Expense item not found on this claim."));
        applyItem(item, claim, claim.getOriginal(), request);
        recalculateTotal(claim);
        return toResponse(claimRepository.save(claim), true);
    }

    @Override
    public SupplementaryClaimResponse removeItem(UUID claimId, UUID itemId) {
        SupplementaryExpenseClaim claim = findClaimForEditing(claimId);
        SupplementaryClaimItem item = claim.getItems().stream()
                .filter(i -> itemId.equals(i.getClaimItemId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Expense item not found on this claim."));
        // The expense's receipts go with it, as a report receipt does when its line is removed; no receipt is left orphaned.
        receiptService.deleteAllForItem(itemId);
        claim.getItems().remove(item);
        recalculateTotal(claim);
        return toResponse(claimRepository.save(claim), true);
    }

    private void addItemTo(SupplementaryExpenseClaim claim, ExpenseReport report, SupplementaryClaimItemRequest request) {
        SupplementaryClaimItem item = SupplementaryClaimItem.builder().claim(claim).build();
        applyItem(item, claim, report, request);
        claim.getItems().add(item);
    }

    private void applyItem(SupplementaryClaimItem item, SupplementaryExpenseClaim claim, ExpenseReport report,
                           SupplementaryClaimItemRequest request) {
        validateItemDate(report, request.expenseDate());
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("Each expense amount must be greater than zero.");
        }
        if (request.categoryId() == null || !categoryRepository.existsById(request.categoryId())) {
            throw new BusinessRuleViolationException("Choose a valid expense category.");
        }
        item.setClaim(claim);
        item.setExpenseDate(request.expenseDate());
        item.setCategoryId(request.categoryId());
        item.setMerchantName(trimmedOrNull(request.merchantName()));
        item.setDescription(trimmedOrNull(request.description()));
        item.setAmount(request.amount().setScale(4, RoundingMode.HALF_UP));
        item.setCurrencyCode(claim.getCurrencyCode());
        item.setEmployeePaymentMethod(request.employeePaymentMethod());
        item.setNotes(trimmedOrNull(request.notes()));
    }

    /**
     * The expense date must fall inside the original report's period, which is the union of its trip dates and its
     * reporting period. Nothing is checked against a submission deadline: none exists for supplementary claims.
     */
    private void validateItemDate(ExpenseReport report, LocalDate expenseDate) {
        if (expenseDate == null) {
            throw new BusinessRuleViolationException("Enter the date of the expense.");
        }
        LocalDate lower = earliest(report.getTripStartDate(), report.getReportingPeriodStart());
        LocalDate upper = latest(report.getTripEndDate(), report.getReportingPeriodEnd());
        if (lower == null || upper == null) {
            throw new BusinessRuleViolationException("The original report has no recorded period, so the expense date cannot be checked.");
        }
        if (expenseDate.isBefore(lower) || expenseDate.isAfter(upper)) {
            throw new BusinessRuleViolationException("The expense date must fall within the original report period ("
                    + lower + " to " + upper + ").");
        }
        if (expenseDate.isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("The expense date cannot be in the future.");
        }
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return Stream.of(a, b).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return Stream.of(a, b).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    // ---------------------------------------------------------------- acknowledge / submit / resubmit / cancel

    @Override
    public SupplementaryClaimResponse acknowledge(UUID claimId) {
        SupplementaryExpenseClaim claim = findClaim(claimId);
        String actor = requireEmployeeId();
        if (!actor.equals(claim.getEmployeeId())) {
            throw new AccessDeniedException("Only the employee named on the claim can acknowledge it.");
        }
        if (!claim.isCreatedOnBehalf()) {
            throw new BusinessRuleViolationException("Only a claim that Finance created for you needs acknowledgment.");
        }
        if (claim.getEmployeeConfirmedAt() != null) {
            throw new BusinessRuleViolationException("You have already acknowledged this claim.");
        }
        requireStatus(claim, SupplementaryExpenseClaim.DRAFT);
        claim.setEmployeeConfirmedAt(LocalDateTime.now());
        claim.setUpdatedAt(LocalDateTime.now());
        record(claim, "ACKNOWLEDGED", claim.getStatus(), claim.getStatus(),
                "Employee acknowledged the claim. This is not an approval.", "EMPLOYEE");
        return toResponse(claimRepository.save(claim), true);
    }

    @Override
    public SupplementaryClaimResponse submit(UUID claimId) {
        SupplementaryExpenseClaim claim = findClaimForSubmission(claimId, SupplementaryExpenseClaim.DRAFT);
        if (claim.getItems().isEmpty()) {
            throw new BusinessRuleViolationException("Add at least one missing expense before submitting.");
        }
        if (claim.isCreatedOnBehalf() && claim.getEmployeeConfirmedAt() == null) {
            throw new BusinessRuleViolationException("The employee must acknowledge this claim before it can be submitted.");
        }
        Eligibility eligibility = evaluate(claim.getOriginal());
        if (!eligibility.eligible()) {
            throw new BusinessRuleViolationException(eligibility.reason());
        }
        requireReceiptOnEveryItem(claim);
        return moveToManager(claim, "SUBMITTED", null);
    }

    @Override
    public SupplementaryClaimResponse resubmit(UUID claimId) {
        SupplementaryExpenseClaim claim = findClaimForSubmission(claimId, SupplementaryExpenseClaim.CLARIFICATION_REQUIRED);
        requireReceiptOnEveryItem(claim);
        // Returns to the stage that asked for clarification, so a Finance query goes back to Finance, not the manager.
        return moveTo(claim, "RESUBMITTED", clarificationReturnStage(claim), null);
    }

    private SupplementaryClaimResponse moveToManager(SupplementaryExpenseClaim claim, String action, String comment) {
        return moveTo(claim, action, SupplementaryExpenseClaim.PENDING_MANAGER, comment);
    }

    private SupplementaryClaimResponse moveTo(SupplementaryExpenseClaim claim, String action, String nextStatus,
                                              String comment) {
        String previous = claim.getStatus();
        LocalDateTime now = LocalDateTime.now();
        claim.setStatus(nextStatus);
        claim.setUpdatedAt(now);
        if (claim.getSubmittedAt() == null || SupplementaryExpenseClaim.DRAFT.equals(previous)) {
            claim.setSubmittedAt(now);
        }
        SupplementaryExpenseClaim saved = claimRepository.save(claim);
        record(saved, action, previous, nextStatus, comment, "EMPLOYEE");
        notifyNextReviewer(saved, nextStatus);
        return toResponse(saved, true);
    }

    @Override
    public SupplementaryClaimResponse cancel(UUID claimId) {
        SupplementaryExpenseClaim claim = lockClaim(claimId);
        String actor = requireEmployeeId();
        if (!actor.equals(claim.getEmployeeId()) && !actor.equals(claim.getCreatedBy())) {
            throw new AccessDeniedException("Only the employee or the creator can cancel this claim.");
        }
        Set<String> cancellable = Set.of(SupplementaryExpenseClaim.DRAFT,
                SupplementaryExpenseClaim.PENDING_MANAGER, SupplementaryExpenseClaim.CLARIFICATION_REQUIRED);
        if (!cancellable.contains(claim.getStatus())) {
            throw new BusinessRuleViolationException("This claim can no longer be cancelled because it is "
                    + claim.getStatus() + ".");
        }
        String previous = claim.getStatus();
        claim.setStatus(SupplementaryExpenseClaim.CANCELLED);
        claim.setUpdatedAt(LocalDateTime.now());
        SupplementaryExpenseClaim saved = claimRepository.save(claim);
        record(saved, "CANCELLED", previous, SupplementaryExpenseClaim.CANCELLED, null,
                actor.equals(claim.getEmployeeId()) ? "EMPLOYEE" : "FINANCE");
        receiptService.detachAllForClaim(saved.getClaimId());
        return toResponse(saved, true);
    }

    // ---------------------------------------------------------------- manager review

    @Override
    @Transactional(readOnly = true)
    public List<SupplementaryClaimResponse> managerQueue() {
        String actor = requireEmployeeId();
        return claimRepository.findByStatusInOrderBySubmittedAtAsc(Set.of(SupplementaryExpenseClaim.PENDING_MANAGER)).stream()
                .filter(c -> actor.equals(managerOf(c.getEmployeeId())))
                .map(c -> toResponse(c, false))
                .toList();
    }

    @Override
    public SupplementaryClaimResponse managerReview(UUID claimId, Decision decision, String comment) {
        SupplementaryExpenseClaim claim = lockClaim(claimId);
        String actor = requireEmployeeId();
        requireStatus(claim, SupplementaryExpenseClaim.PENDING_MANAGER);
        requireManagerOf(claim, actor);
        requireNotSelfOrCreator(claim, actor, "approve or reject");
        return applyDecision(claim, decision, comment, "MANAGER", SupplementaryExpenseClaim.PENDING_MANAGER,
                SupplementaryExpenseClaim.PENDING_FINANCE, SupplementaryExpenseClaim.MANAGER_REJECTED);
    }

    // ---------------------------------------------------------------- Finance review

    @Override
    @Transactional(readOnly = true)
    public List<SupplementaryClaimResponse> financeQueue() {
        requireFinanceRole();
        return claimRepository.findByStatusInOrderBySubmittedAtAsc(Set.of(SupplementaryExpenseClaim.PENDING_FINANCE)).stream()
                .map(c -> toResponse(c, false))
                .toList();
    }

    @Override
    public SupplementaryClaimResponse financeReview(UUID claimId, Decision decision, String comment) {
        requireFinanceRole();
        SupplementaryExpenseClaim claim = lockClaim(claimId);
        String actor = currentUserService.getActorId();
        requireStatus(claim, SupplementaryExpenseClaim.PENDING_FINANCE);
        requireNotSelfOrCreator(claim, actor, "approve or reject");
        return applyDecision(claim, decision, comment, "FINANCE", SupplementaryExpenseClaim.PENDING_FINANCE,
                SupplementaryExpenseClaim.PAYMENT_PENDING, SupplementaryExpenseClaim.FINANCE_REJECTED);
    }

    /**
     * Applies an approve, reject or clarify decision. A clarification records the stage it came from, so the employee's
     * resubmission returns to that same stage.
     */
    private SupplementaryClaimResponse applyDecision(SupplementaryExpenseClaim claim, Decision decision, String comment,
                                                     String role, String fromStatus, String approvedStatus,
                                                     String rejectedStatus) {
        String previous = claim.getStatus();
        String reason = trimmedOrNull(comment);
        LocalDateTime now = LocalDateTime.now();
        String next;
        String action;
        switch (decision) {
            case APPROVE -> {
                next = approvedStatus;
                action = "APPROVED";
            }
            case REJECT -> {
                requireComment(reason, "a rejection");
                next = rejectedStatus;
                action = "REJECTED";
            }
            case CLARIFY -> {
                requireComment(reason, "a clarification request");
                next = SupplementaryExpenseClaim.CLARIFICATION_REQUIRED;
                action = "CLARIFICATION_REQUESTED";
            }
            default -> throw new BusinessRuleViolationException("Unknown decision.");
        }
        claim.setStatus(next);
        claim.setUpdatedAt(now);
        // The history row keeps the stage the decision came from. For a clarification that is the stage resubmission returns to.
        SupplementaryExpenseClaim saved = claimRepository.save(claim);
        record(saved, action, previous, next, reason, role);
        if (SupplementaryExpenseClaim.PAYMENT_PENDING.equals(next)) {
            // Finance approval settles the claim: against the advance that holds the carried balance, then by AP.
            saved = settleAgainstAdvance(saved);
        }
        if (decision == Decision.REJECT) {
            receiptService.detachAllForClaim(saved.getClaimId());
        }
        notifyAfterDecision(saved, decision, role);
        return toResponse(saved, true);
    }

    private void notifyAfterDecision(SupplementaryExpenseClaim claim, Decision decision, String role) {
        switch (decision) {
            case APPROVE -> {
                if ("MANAGER".equals(role)) {
                    notifyRole(NotificationService.ROLE_FINANCE, claim, "SUPP_CLAIM_FINANCE_REVIEW",
                            "Supplementary claim waiting for Finance review",
                            "A supplementary claim was approved by the manager and needs Finance review.");
                } else {
                    notifyRole(NotificationService.ROLE_AP, claim, "SUPP_CLAIM_PAYMENT",
                            "Supplementary claim approved for payment",
                            "A supplementary claim was approved by Finance and is ready for an AP payment.");
                    notifyEmployee(claim, "SUPP_CLAIM_APPROVED", "Supplementary claim approved",
                            "Your supplementary claim was approved and is waiting for payment.", NotificationCategory.COMPLETED);
                }
            }
            case REJECT -> notifyEmployee(claim, "SUPP_CLAIM_REJECTED", "Supplementary claim rejected",
                    "Your supplementary claim was rejected. See the review comment.", NotificationCategory.FAILED);
            case CLARIFY -> notifyEmployee(claim, "SUPP_CLAIM_CLARIFY", "Supplementary claim needs clarification",
                    "A reviewer asked for clarification on your supplementary claim. Update it and resubmit.",
                    NotificationCategory.ACTION_REQUIRED);
        }
    }

    // ---------------------------------------------------------------- reads

    @Override
    @Transactional(readOnly = true)
    public SupplementaryClaimResponse get(UUID claimId) {
        SupplementaryExpenseClaim claim = findClaim(claimId);
        String actor = requireEmployeeId();
        boolean isOwner = actor.equals(claim.getEmployeeId()) || actor.equals(claim.getCreatedBy());
        boolean isReviewer = actor.equals(managerOf(claim.getEmployeeId()))
                || isFinanceRole();
        if (!isOwner && !isReviewer) {
            throw new AccessDeniedException("You do not have access to this supplementary claim.");
        }
        return toResponse(claim, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplementaryClaimResponse> listMine() {
        String actor = requireEmployeeId();
        return claimRepository.findByEmployeeIdOrderByCreatedAtDesc(actor).stream()
                .map(c -> toResponse(c, false))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplementaryClaimResponse> listForReport(UUID reportId) {
        String actor = requireEmployeeId();
        ExpenseReport report = findReport(reportId);
        if (!actor.equals(report.getEmployeeId())) {
            throw new AccessDeniedException("You can see supplementary claims only for your own report.");
        }
        return claimRepository.findByOriginal_ReportIdAndStatusIn(reportId, ALL_STATUSES).stream()
                .sorted(Comparator.comparing(SupplementaryExpenseClaim::getCreatedAt).reversed())
                .map(c -> toResponse(c, false))
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    private ExpenseReport findReport(UUID reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found."));
    }

    private SupplementaryExpenseClaim findClaim(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplementary claim not found."));
    }

    private SupplementaryExpenseClaim lockClaim(UUID claimId) {
        return claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplementary claim not found."));
    }

    /** Items may change only by the employee or the Finance creator, and only while the claim is draft or returned. */
    private SupplementaryExpenseClaim findClaimForEditing(UUID claimId) {
        SupplementaryExpenseClaim claim = findClaim(claimId);
        String actor = requireEmployeeId();
        if (!actor.equals(claim.getEmployeeId()) && !actor.equals(claim.getCreatedBy())) {
            throw new AccessDeniedException("Only the employee or the creator can change this claim.");
        }
        if (!EDITABLE.contains(claim.getStatus())) {
            throw new BusinessRuleViolationException("This claim is " + claim.getStatus() + " and can no longer be changed.");
        }
        return claim;
    }

    /** Submit and resubmit are the employee's own action on their own claim. */
    private SupplementaryExpenseClaim findClaimForSubmission(UUID claimId, String requiredStatus) {
        SupplementaryExpenseClaim claim = lockClaim(claimId);
        String actor = requireEmployeeId();
        if (!actor.equals(claim.getEmployeeId())) {
            throw new AccessDeniedException("Only the employee named on the claim can submit it.");
        }
        requireStatus(claim, requiredStatus);
        return claim;
    }

    private void requireStatus(SupplementaryExpenseClaim claim, String expected) {
        if (!expected.equals(claim.getStatus())) {
            throw new BusinessRuleViolationException("This action needs the claim to be " + expected
                    + ", but it is " + claim.getStatus() + ".");
        }
    }

    private void requireManagerOf(SupplementaryExpenseClaim claim, String actor) {
        String manager = managerOf(claim.getEmployeeId());
        if (manager == null || !manager.equals(actor)) {
            throw new AccessDeniedException("Only the employee's reporting manager can review this claim at this stage.");
        }
    }

    private void requireNotSelfOrCreator(SupplementaryExpenseClaim claim, String actor, String verb) {
        if (actor.equals(claim.getEmployeeId())) {
            throw new AccessDeniedException("You cannot " + verb + " your own claim.");
        }
        if (actor.equals(claim.getCreatedBy())) {
            throw new AccessDeniedException("The person who created this claim cannot " + verb + " it.");
        }
    }

    /** Finance review uses the same role rule as line item verification: FINANCE_EXECUTIVE or FINANCE. */
    private void requireFinanceRole() {
        if (!isFinanceRole()) {
            throw new AccessDeniedException("Only Finance can review supplementary claims.");
        }
    }

    private boolean isFinanceRole() {
        CurrentUser caller = currentUserService.getCurrentUser();
        return caller != null && caller.roles() != null && caller.roles().stream()
                .anyMatch(r -> RoleConstants.FINANCE.equalsIgnoreCase(r) || RoleConstants.FINANCE_EXECUTIVE.equalsIgnoreCase(r));
    }

    private void requirePermission(String permission) {
        List<String> permissions = currentUserService.getPermissions();
        if (permissions == null || !permissions.contains(permission)) {
            throw new AccessDeniedException("Missing permission " + permission + ".");
        }
    }

    private void requireComment(String reason, String action) {
        if (reason == null || reason.length() < MIN_DECISION_COMMENT) {
            throw new BusinessRuleViolationException("Give a reason for " + action + " (at least "
                    + MIN_DECISION_COMMENT + " characters).");
        }
    }

    /** The stage a clarification came from, read from history. Defaults to the manager when none is recorded. */
    private String clarificationReturnStage(SupplementaryExpenseClaim claim) {
        return historyRepository.findByClaim_ClaimIdOrderByCreatedAtAsc(claim.getClaimId()).stream()
                .filter(h -> "CLARIFICATION_REQUESTED".equals(h.getAction()))
                .reduce((first, second) -> second)
                .map(SupplementaryClaimHistory::getPreviousStatus)
                .orElse(SupplementaryExpenseClaim.PENDING_MANAGER);
    }

    /** The manager of record for an employee, or null when none is recorded. Null never matches an actor (fail closed). */
    private String managerOf(String employeeId) {
        if (employeeId == null) {
            return null;
        }
        return employeeCacheRepository.findByEmployeeId(employeeId)
                .map(EmployeeCache::getManagerEmployeeId)
                .orElse(null);
    }

    private String requireEmployeeId() {
        String id = currentUserService.getEmployeeId();
        if (id == null || id.isBlank()) {
            throw new AccessDeniedException("Your account has no employee id, so it cannot use supplementary claims.");
        }
        return id;
    }

    /** Every expense needs at least one receipt attached before the claim can be submitted or resubmitted. */
    private void requireReceiptOnEveryItem(SupplementaryExpenseClaim claim) {
        for (SupplementaryClaimItem item : claim.getItems()) {
            if (receiptRepository.findBySupplementaryClaimItem_ClaimItemId(item.getClaimItemId()).isEmpty()) {
                throw new BusinessRuleViolationException("Attach a receipt to every expense before submitting. "
                        + "Missing: " + (item.getMerchantName() == null ? "an expense on " + item.getExpenseDate() : item.getMerchantName()) + ".");
            }
        }
    }

    private void recalculateTotal(SupplementaryExpenseClaim claim) {
        BigDecimal total = claim.getItems().stream()
                .map(SupplementaryClaimItem::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
        claim.setTotalAmount(total);
    }

    private static String newClaimNumber() {
        return "SUP-" + LocalDate.now().format(CLAIM_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Writes the append-only history row and an audit row for the same action. */
    private void record(SupplementaryExpenseClaim claim, String action, String previous, String next, String comment,
                        String role) {
        String actor = currentUserService.getActorId();
        LocalDateTime now = LocalDateTime.now();
        historyRepository.save(SupplementaryClaimHistory.builder()
                .claim(claim)
                .actorId(actor)
                .actorRole(role)
                .action(action)
                .previousStatus(previous)
                .newStatus(next)
                .comment(comment)
                .createdAt(now)
                .build());
        auditLogRepository.save(AuditLog.builder()
                .entityName("SupplementaryExpenseClaim")
                .entityId(claim.getClaimId())
                .action(action)
                .oldValue(previous)
                .newValue(next)
                .reason(comment)
                .performedBy(actor)
                .performedAt(now)
                .source(auditSourceFor(role))
                .build());
    }

    private static AuditSource auditSourceFor(String role) {
        return switch (role) {
            case "FINANCE" -> AuditSource.FINANCE;
            case "MANAGER", "EMPLOYEE" -> AuditSource.EMPLOYEE;
            default -> AuditSource.SYSTEM;
        };
    }

    private void notifyNextReviewer(SupplementaryExpenseClaim claim, String nextStatus) {
        if (SupplementaryExpenseClaim.PENDING_MANAGER.equals(nextStatus)) {
            String manager = managerOf(claim.getEmployeeId());
            if (manager != null) {
                notifyEmployeeId(manager, claim, "SUPP_CLAIM_MANAGER_REVIEW", "Supplementary claim to review",
                        "A supplementary claim is waiting for your review.", NotificationCategory.ACTION_REQUIRED);
            }
        } else if (SupplementaryExpenseClaim.PENDING_FINANCE.equals(nextStatus)) {
            notifyRole(NotificationService.ROLE_FINANCE, claim, "SUPP_CLAIM_FINANCE_REVIEW",
                    "Supplementary claim waiting for Finance review",
                    "A supplementary claim was resubmitted and needs Finance review.");
        }
    }

    private void notifyEmployee(SupplementaryExpenseClaim claim, String eventType, String title, String message,
                                NotificationCategory category) {
        notifyEmployeeId(claim.getEmployeeId(), claim, eventType, title, message, category);
    }

    private void notifyEmployeeId(String employeeId, SupplementaryExpenseClaim claim, String eventType, String title,
                                  String message, NotificationCategory category) {
        notificationService.notifyEmployee(employeeId, draft(claim, eventType, title, message, category));
    }

    private void notifyRole(String role, SupplementaryExpenseClaim claim, String eventType, String title, String message) {
        notificationService.notifyRole(role, draft(claim, eventType, title, message, NotificationCategory.ACTION_REQUIRED));
    }

    private NotificationDraft draft(SupplementaryExpenseClaim claim, String eventType, String title, String message,
                                    NotificationCategory category) {
        return NotificationDraft.builder()
                .category(category)
                .eventType(eventType)
                .title(title)
                .message(message)
                .reportId(claim.getOriginal().getReportId())
                .reportNumber(claim.getClaimNumber())
                .amount(claim.getTotalAmount())
                .currencyCode(claim.getCurrencyCode())
                .statusLabel(claim.getStatus())
                .link("/expense-management/supplementary-claims/" + claim.getClaimId())
                .build();
    }

    private SupplementaryClaimResponse toResponse(SupplementaryExpenseClaim claim, boolean withHistory) {
        ExpenseReport report = claim.getOriginal();
        Map<UUID, List<SupplementaryReceiptResponse>> receiptsByItem = receiptRepository
                .findBySupplementaryClaimItem_Claim_ClaimId(claim.getClaimId()).stream()
                .collect(Collectors.groupingBy(
                        r -> r.getSupplementaryClaimItem().getClaimItemId(),
                        Collectors.mapping(r -> new SupplementaryReceiptResponse(r.getReceiptId(), r.getOriginalFileName(),
                                r.getAttachmentKind(), r.getFileSize(), r.getUploadedAt()), Collectors.toList())));
        List<SupplementaryClaimItemResponse> items = claim.getItems().stream()
                .sorted(Comparator.comparing(SupplementaryClaimItem::getExpenseDate))
                .map(i -> new SupplementaryClaimItemResponse(i.getClaimItemId(), i.getExpenseDate(), i.getCategoryId(),
                        i.getMerchantName(), i.getDescription(), i.getAmount(), i.getCurrencyCode(),
                        i.getEmployeePaymentMethod() == null ? null : i.getEmployeePaymentMethod().name(), i.getNotes(),
                        receiptsByItem.getOrDefault(i.getClaimItemId(), List.of())))
                .toList();
        List<SupplementaryClaimHistoryResponse> history = withHistory
                ? historyRepository.findByClaim_ClaimIdOrderByCreatedAtAsc(claim.getClaimId()).stream()
                .map(h -> new SupplementaryClaimHistoryResponse(h.getActorId(), h.getActorRole(), h.getAction(),
                        h.getPreviousStatus(), h.getNewStatus(), h.getComment(), h.getCreatedAt()))
                .collect(Collectors.toList())
                : List.of();
        return new SupplementaryClaimResponse(claim.getClaimId(), claim.getClaimNumber(), report.getReportId(),
                report.getReportNumber(), earliest(report.getTripStartDate(), report.getReportingPeriodStart()),
                latest(report.getTripEndDate(), report.getReportingPeriodEnd()),
                claim.getEmployeeId(), claim.getCreatedBy(), claim.isCreatedOnBehalf(),
                claim.getCreationReason(), claim.getEmployeeConfirmedAt(), claim.getLateReasonCode(),
                claim.getLateReasonText(), claim.getTotalAmount(), claim.getCurrencyCode(), claim.getStatus(),
                claim.getSubmittedAt(), claim.getSettledAt(), claim.getCreatedAt(),
                paymentService.paymentStatusOf(claim.getClaimId()), paymentService.paymentReferenceOf(claim.getClaimId()),
                claim.getSettledAdvanceId(), claim.getAdvanceOffsetAmount(),
                items, history);
    }
}
