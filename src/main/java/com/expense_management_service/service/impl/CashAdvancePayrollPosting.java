package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Posts a verified payroll deduction to a cash advance balance. Used by salary deduction requests and by payroll
 * outcomes on automated recovery cases, so both write the same repayment record and the same balance rules.
 *
 * <p>The caller must already hold the advance row lock. This class does not lock on its own.
 */
@Service
@RequiredArgsConstructor
public class CashAdvancePayrollPosting {

    /** Payment mode stored on the repayment. Matches the value used by the salary deduction and recovery flows. */
    public static final String PAYMENT_MODE = "PAYROLL";

    public static final Set<String> EVIDENCE_TYPES = Set.of("PAYROLL_EMAIL", "PAYSLIP", "PAYROLL_RECORD", "OTHER");

    private static final long MAX_EVIDENCE_BYTES = 10L * 1024 * 1024;

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceRepaymentRepository repaymentRepository;
    private final CashAdvanceBudgetMovements budgetMovements;
    private final CashAdvanceBalanceLedger balanceLedger;
    private final StorageService storageService;

    public static BigDecimal currentBalance(CashAdvance advance) {
        return advance.getOutstandingBalance() != null ? advance.getOutstandingBalance() : advance.getAmount();
    }

    public static int decimals(CashAdvance advance) {
        return advance.getCurrency() != null ? advance.getCurrency().getDecimalPlaces() : 2;
    }

    /**
     * Reduces the balance by {@code actual} and writes the repayment. A deduction larger than the balance is refused,
     * so the balance can never go negative. The advance is CLOSED only when the balance reaches zero.
     */
    public BigDecimal post(CashAdvance advance, BigDecimal actual, String payrollReference, String evidenceKey,
                           String note, String actor, String reason) {
        if (actual == null || actual.signum() <= 0) {
            throw new IllegalArgumentException("A payroll deduction to post must be greater than zero.");
        }
        int decimals = decimals(advance);
        BigDecimal balance = currentBalance(advance);
        if (actual.compareTo(balance) > 0) {
            throw new BusinessRuleViolationException("The actual deduction (" + actual
                    + ") cannot exceed the outstanding balance (" + balance + ").");
        }
        BigDecimal newBalance = CashAdvanceBalanceRules.requireNonNegative(balance.subtract(actual), decimals);
        advance.setOutstandingBalance(newBalance);
        advance.setStatus(CashAdvanceBalanceRules.statusAfterBalance(newBalance, decimals));
        budgetMovements.releaseAllocation(advance, actual);
        cashAdvanceRepository.save(advance);

        repaymentRepository.save(CashAdvanceRepayment.builder()
                .cashAdvance(advance)
                .amount(actual)
                .paymentMethod(PAYMENT_MODE)
                .paymentReference(blankToNull(payrollReference))
                .repaidBy(actor)
                .repaidAt(LocalDateTime.now())
                .notes(blankToNull(note))
                .paymentMode(PAYMENT_MODE)
                .proofReference(evidenceKey)
                .confirmationNote(blankToNull(note))
                .build());

        balanceLedger.recordChange("CashAdvance", advance.getAdvanceId(), "PAYROLL_DEDUCTION_POSTED",
                "Balance: " + balance, "Balance: " + newBalance, actor, reason);
        return newBalance;
    }

    /**
     * A Payroll reference is optional only when alternative evidence is recorded: the evidence type and either an
     * attached file or a note describing it. Nothing is invented when the reference is missing.
     */
    public static void requireReferenceOrEvidence(String reference, String evidenceType, boolean hasFile, String evidenceNote) {
        if (blankToNull(reference) != null) {
            return;
        }
        if (evidenceType == null || !EVIDENCE_TYPES.contains(evidenceType)) {
            throw new BusinessRuleViolationException(
                    "Enter the Payroll confirmation reference, or choose the evidence type (Payroll email, payslip, payroll record or other).");
        }
        if (!hasFile && blankToNull(evidenceNote) == null) {
            throw new BusinessRuleViolationException(
                    "Attach the evidence file, or describe it in the evidence note, when there is no Payroll reference.");
        }
    }

    /** Stores an evidence file under the advance. Accepts PDF, JPG and PNG up to 10 MB, like the other proofs. */
    public String storeEvidence(UUID advanceId, MultipartFile file) {
        if (file.getContentType() == null || file.getSize() > MAX_EVIDENCE_BYTES) {
            throw new BusinessRuleViolationException("Upload a PDF, JPG or PNG file of 10 MB or less.");
        }
        String extension = CashAdvanceDisbursementRules.proofExtension(file);
        if (extension == null) {
            throw new BusinessRuleViolationException("Upload a PDF, JPG or PNG file.");
        }
        String key = "salary-deductions/" + advanceId + "/payroll-" + UUID.randomUUID() + "." + extension;
        storageService.upload(key, file);
        return key;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
