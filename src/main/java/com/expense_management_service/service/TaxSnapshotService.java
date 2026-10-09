package com.expense_management_service.service;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the tax snapshot onto an expense line (BR-TAX-009: recalculated on every save while the
 * report is editable) and freezes it when the report is submitted.
 */
public interface TaxSnapshotService {

    /**
     * Calculates the line's tax from its amount, date and category (or {@code requestedTaxCodeId})
     * and stores code, rate, components, final / calculated tax, source, ITC and base-currency values.
     * The line's currency, exchange rate and base amount must already be set.
     *
     * @param enteredTax     what the employee entered; null = take the calculation
     * @param previous       {@link #describe} of the line before this edit (null on create) - used to
     *                       audit tax changes made while correcting a previously submitted report
     * @param baseScale      minor units of the organization base currency
     */
    void applySnapshot(ExpenseLineItem line, UUID requestedTaxCodeId, BigDecimal enteredTax, String overrideReason,
                       Map<String, Object> previous, int baseScale);

    /**
     * Submission gate + freeze: rejects the submission if any line's tax override is missing its
     * reason (VR-TAX-09), then stamps every line's {@code taxSnapshotAt} and audits it.
     */
    void freezeForSubmission(ExpenseReport report);

    /**
     * Records the tax OCR read off the line's receipt (copied so comparison survives OCR re-runs),
     * re-validates the line and audits it. Called when a receipt is confirmed.
     */
    void attachOcrEvidence(ExpenseLineItem line, BigDecimal ocrTax, BigDecimal ocrConfidence);

    /** Finance verified the line: tax status FINANCE_VERIFIED (an adjusted line stays FINANCE_ADJUSTED). */
    void markFinanceVerified(ExpenseLineItem line);

    /**
     * FINANCE_EXECUTIVE correction of one line's tax (BR-TAX-012): code and/or tax and/or ITC %,
     * never the gross amount. Recomputes components, recoverable and base values, marks the line
     * FINANCE_ADJUSTED and audits it with the reason.
     */
    void financeAdjust(ExpenseLineItem line, UUID taxCodeId, BigDecimal taxAmount, BigDecimal itcRecoverablePercent, String reason);

    /** Audits that a Finance verification was reopened because the line's tax changed during correction. */
    void recordFinanceVerificationReset(ExpenseLineItem line);

    /** Compact, comparable view of a line's tax (for audit old/new values). */
    Map<String, Object> describe(ExpenseLineItem line);
}
