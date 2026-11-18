package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SupplementaryPaymentConfirmRequest;
import com.expense_management_service.dto.response.SupplementaryPaymentResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.SupplementaryClaimHistory;
import com.expense_management_service.entity.SupplementaryExpenseClaim;
import com.expense_management_service.entity.SupplementaryPayment;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.repository.ApPaymentBatchRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.SupplementaryClaimHistoryRepository;
import com.expense_management_service.repository.SupplementaryExpenseClaimRepository;
import com.expense_management_service.repository.SupplementaryPaymentRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import com.expense_management_service.service.SupplementaryPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SupplementaryPaymentServiceImpl implements SupplementaryPaymentService {

    private final SupplementaryPaymentRepository paymentRepository;
    private final SupplementaryExpenseClaimRepository claimRepository;
    private final SupplementaryClaimHistoryRepository historyRepository;
    private final ApPaymentBatchRepository apPaymentBatchRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;

    @Override
    public void requestPayment(SupplementaryExpenseClaim claim) {
        // One request per claim: a second Finance approval, or a retry, cannot create a second payment.
        if (paymentRepository.existsByClaim_ClaimId(claim.getClaimId())) {
            throw new BusinessRuleViolationException("A payment has already been requested for this claim.");
        }
        String actor = currentUserService.getActorId();
        LocalDateTime now = LocalDateTime.now();
        SupplementaryPayment payment = paymentRepository.save(SupplementaryPayment.builder()
                .claim(claim)
                .amount(claim.getTotalAmount().setScale(4, RoundingMode.HALF_UP))
                .currencyCode(claim.getCurrencyCode())
                .status(SupplementaryPayment.REQUESTED)
                .requestedBy(actor)
                .requestedAt(now)
                .build());
        audit(payment.getPaymentId(), "PAYMENT_REQUESTED", null, SupplementaryPayment.REQUESTED, null, actor, now);
        notificationService.notifyRole(NotificationService.ROLE_AP, NotificationDraft.builder()
                .category(NotificationCategory.ACTION_REQUIRED)
                .eventType("SUPP_CLAIM_PAYMENT_REQUESTED")
                .title("Supplementary claim ready for payment")
                .message("A supplementary claim was approved by Finance. Record the payment once it is made.")
                .reportId(claim.getOriginal().getReportId())
                .reportNumber(claim.getClaimNumber())
                .amount(payment.getAmount())
                .currencyCode(payment.getCurrencyCode())
                .link("/expense-management/ap/supplementary-payments")
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplementaryPaymentResponse> queue() {
        return paymentRepository.findByStatusOrderByRequestedAtAsc(SupplementaryPayment.REQUESTED).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public SupplementaryPaymentResponse confirm(UUID paymentId, SupplementaryPaymentConfirmRequest request) {
        SupplementaryPayment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplementary payment not found."));
        if (!SupplementaryPayment.REQUESTED.equals(payment.getStatus())) {
            // Duplicate confirmation: the first one already settled the claim, so nothing is recorded twice.
            throw new BusinessRuleViolationException("This payment is already " + payment.getStatus() + " and cannot be paid twice.");
        }
        SupplementaryExpenseClaim claim = claimRepository.findByIdForUpdate(payment.getClaim().getClaimId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplementary claim not found."));
        if (!SupplementaryExpenseClaim.PAYMENT_PENDING.equals(claim.getStatus())) {
            throw new BusinessRuleViolationException("The claim is " + claim.getStatus() + ", so its payment cannot be confirmed.");
        }

        String actor = currentUserService.getActorId();
        if (actor == null || actor.isBlank()) {
            throw new AccessDeniedException("Your account has no employee id, so it cannot confirm payments.");
        }
        // Separation of duties: the employee is never paid by their own confirmation, and the Finance user who approved
        // the claim does not also confirm its payment.
        if (actor.equals(claim.getEmployeeId())) {
            throw new AccessDeniedException("You cannot confirm a payment on your own claim.");
        }
        if (actor.equals(payment.getRequestedBy())) {
            throw new AccessDeniedException("The Finance user who approved the claim cannot also confirm its payment.");
        }

        BigDecimal paid = request.amountPaid().setScale(4, RoundingMode.HALF_UP);
        if (paid.subtract(payment.getAmount()).abs().compareTo(SettlementAmounts.PAYMENT_TOLERANCE) > 0) {
            throw new BusinessRuleViolationException("The amount paid " + paid.toPlainString()
                    + " does not match the claim amount " + payment.getAmount().toPlainString() + ".");
        }

        String reference = request.bankReference().trim();
        if (paymentRepository.existsByBankReference(reference)
                || apPaymentBatchRepository.existsByBankReference(reference)
                || expenseReportRepository.existsByPaymentReference(reference)) {
            throw new BusinessRuleViolationException("Bank reference " + reference
                    + " is already recorded. Each payment needs its own reference.");
        }

        LocalDateTime now = LocalDateTime.now();
        payment.setStatus(SupplementaryPayment.PAID);
        payment.setPaidBy(actor);
        payment.setPaidAt(now);
        payment.setBankReference(reference);
        payment.setPaymentMethod(request.paymentMethod().name());
        payment.setPaymentDate(request.paymentDate());
        payment.setRemarks(request.remarks() == null || request.remarks().isBlank() ? null : request.remarks().trim());
        paymentRepository.save(payment);

        String previous = claim.getStatus();
        claim.setStatus(SupplementaryExpenseClaim.SETTLED);
        claim.setSettledAt(now);
        claim.setUpdatedAt(now);
        claimRepository.save(claim);

        historyRepository.save(SupplementaryClaimHistory.builder()
                .claim(claim)
                .actorId(actor)
                .actorRole("AP")
                .action("PAYMENT_CONFIRMED")
                .previousStatus(previous)
                .newStatus(SupplementaryExpenseClaim.SETTLED)
                .comment("Paid " + paid.toPlainString() + " " + payment.getCurrencyCode() + " by " + payment.getPaymentMethod()
                        + ", reference " + reference)
                .createdAt(now)
                .build());
        audit(payment.getPaymentId(), "PAYMENT_CONFIRMED", SupplementaryPayment.REQUESTED, SupplementaryPayment.PAID,
                "reference=" + reference + " amount=" + paid.toPlainString(), actor, now);

        notificationService.notifyEmployee(claim.getEmployeeId(), NotificationDraft.builder()
                .category(NotificationCategory.COMPLETED)
                .eventType("SUPP_CLAIM_PAID")
                .title("Supplementary claim paid")
                .message("Your supplementary claim was paid. Reference " + reference + ".")
                .reportId(claim.getOriginal().getReportId())
                .reportNumber(claim.getClaimNumber())
                .amount(payment.getAmount())
                .currencyCode(payment.getCurrencyCode())
                .link("/expense-management/expenses/supplementary/" + claim.getClaimId())
                .build());
        return toResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public String paymentStatusOf(UUID claimId) {
        return paymentRepository.findByClaim_ClaimId(claimId)
                .map(SupplementaryPayment::getStatus)
                .orElse("NONE");
    }

    @Override
    @Transactional(readOnly = true)
    public String paymentReferenceOf(UUID claimId) {
        return paymentRepository.findByClaim_ClaimId(claimId)
                .map(SupplementaryPayment::getBankReference)
                .orElse(null);
    }

    private void audit(UUID paymentId, String action, String oldValue, String newValue, String reason, String actor,
                       LocalDateTime at) {
        auditLogRepository.save(AuditLog.builder()
                .entityName("SupplementaryPayment")
                .entityId(paymentId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .performedBy(actor)
                .performedAt(at)
                .source(AuditSource.FINANCE)
                .build());
    }

    private SupplementaryPaymentResponse toResponse(SupplementaryPayment p) {
        return new SupplementaryPaymentResponse(p.getPaymentId(), p.getClaim().getClaimId(), p.getClaim().getClaimNumber(),
                p.getClaim().getEmployeeId(), p.getAmount(), p.getCurrencyCode(), p.getStatus(), p.getRequestedBy(),
                p.getRequestedAt(), p.getPaidBy(), p.getPaidAt(), p.getBankReference(), p.getPaymentMethod(),
                p.getPaymentDate());
    }
}
