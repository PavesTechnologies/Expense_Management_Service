package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.findAllFieldsByLabelContaining;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizedConfidenceOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 6/7: extracts the receipt's total (grand total / amount payable) and subtotal.
 * <p>
 * <b>Total</b> — candidates are collected in this priority order: {@code GRAND TOTAL} label, the
 * standard {@code TOTAL} ExpenseType, {@code AMOUNT DUE}/{@code BALANCE DUE}, {@code NET AMOUNT}/
 * {@code NET PAYABLE}, {@code PAYABLE}/{@code AMOUNT PAYABLE}, and finally {@code UNPAID}/
 * {@code FINAL TOTAL} (kept for templates that use only those). Whenever both a subtotal and a
 * tax amount are already known, whichever candidate's value reconciles with
 * {@code subtotal + tax} wins <em>regardless of tier</em> — a number that actually adds up is
 * stronger evidence than a label alone, and this is what stops a same-named "Total" field that
 * happens to just repeat the subtotal from beating a correctly-labeled "Balance Due"/"Unpaid"
 * field elsewhere on the same receipt. The priority order above only decides ties when
 * reconciliation can't (no subtotal/tax known, multiple candidates reconcile, or none do). The
 * subtotal is never itself treated as a total candidate, so "never use subtotal if Grand Total
 * exists" holds by construction, not by a special-case check.
 * <p>
 * <b>Subtotal</b> — standard {@code SUBTOTAL} ExpenseType, then a label scan ({@code SUBTOTAL}/
 * {@code SUB TOTAL}, {@code TAXABLE AMOUNT}, {@code NET AMOUNT BEFORE TAX}). Fields already
 * claimed by this label scan are excluded from the Total label scan above, so a "Net Amount
 * Before Tax" field can never be mistaken for the Total tier's "Net Amount".
 */
final class AmountExtractor {

    private static final String FIELD_TOTAL = "TOTAL";
    private static final String FIELD_SUBTOTAL = "SUBTOTAL";

    private static final List<String> SUBTOTAL_LABEL_KEYWORDS = List.of(
            "SUBTOTAL", "TAXABLE AMOUNT", "NET AMOUNT BEFORE TAX");

    /** Priority order for label-matched total candidates, checked after the standard TOTAL type wins no reconciliation. */
    private static final List<String> TOTAL_LABEL_KEYWORDS_AFTER_GRAND_TOTAL = List.of(
            "AMOUNT DUE", "BALANCE DUE", "NET AMOUNT", "NET PAYABLE", "PAYABLE", "AMOUNT PAYABLE",
            "UNPAID", "FINAL TOTAL");

    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.05");

    ExtractionResult<BigDecimal> extractSubtotal(ExpenseFieldIndex index) {
        ExpenseField typed = index.firstByType(FIELD_SUBTOTAL);
        BigDecimal typedAmount = ReceiptFieldParsingUtils.parseAmount(textOf(typed), FIELD_SUBTOTAL);
        if (typedAmount != null) {
            return ExtractionResult.of(typedAmount, normalizedConfidenceOf(typed));
        }

        for (String keyword : SUBTOTAL_LABEL_KEYWORDS) {
            for (ExpenseField field : findAllFieldsByLabelContaining(index.allFields(), keyword)) {
                BigDecimal amount = ReceiptFieldParsingUtils.parseAmount(textOf(field), keyword);
                if (amount != null) {
                    return ExtractionResult.of(amount, normalizedConfidenceOf(field));
                }
            }
        }
        return ExtractionResult.empty();
    }

    ExtractionResult<BigDecimal> extractTotal(ExpenseFieldIndex index, BigDecimal subtotal, BigDecimal taxAmount) {
        Set<ExpenseField> subtotalClaimedFields = subtotalLabelMatchedFields(index);

        Map<String, CandidateAmount> candidatesInPriorityOrder = new LinkedHashMap<>();

        addLabelCandidates(candidatesInPriorityOrder, index, "GRAND TOTAL", subtotalClaimedFields);

        ExpenseField totalTypeField = index.firstByType(FIELD_TOTAL);
        BigDecimal totalTypeAmount = ReceiptFieldParsingUtils.parseAmount(textOf(totalTypeField), FIELD_TOTAL);
        if (totalTypeAmount != null) {
            candidatesInPriorityOrder.putIfAbsent(FIELD_TOTAL,
                    new CandidateAmount(totalTypeAmount, normalizedConfidenceOf(totalTypeField)));
        }

        for (String keyword : TOTAL_LABEL_KEYWORDS_AFTER_GRAND_TOTAL) {
            addLabelCandidates(candidatesInPriorityOrder, index, keyword, subtotalClaimedFields);
        }

        if (candidatesInPriorityOrder.isEmpty()) {
            return ExtractionResult.empty();
        }

        if (subtotal != null && taxAmount != null) {
            BigDecimal expectedTotal = subtotal.add(taxAmount);
            for (CandidateAmount candidate : candidatesInPriorityOrder.values()) {
                if (candidate.amount().subtract(expectedTotal).abs().compareTo(RECONCILIATION_TOLERANCE) <= 0) {
                    return ExtractionResult.of(candidate.amount(), candidate.confidence());
                }
            }
        }

        CandidateAmount best = candidatesInPriorityOrder.values().iterator().next();
        return ExtractionResult.of(best.amount(), best.confidence());
    }

    private void addLabelCandidates(Map<String, CandidateAmount> candidates, ExpenseFieldIndex index,
                                     String keyword, Set<ExpenseField> excluded) {
        for (ExpenseField field : findAllFieldsByLabelContaining(index.allFields(), keyword)) {
            if (excluded.contains(field)) {
                continue;
            }
            BigDecimal amount = ReceiptFieldParsingUtils.parseAmount(textOf(field), keyword);
            if (amount != null) {
                candidates.putIfAbsent(keyword, new CandidateAmount(amount, normalizedConfidenceOf(field)));
                break;
            }
        }
    }

    private Set<ExpenseField> subtotalLabelMatchedFields(ExpenseFieldIndex index) {
        Set<ExpenseField> claimed = new java.util.HashSet<>();
        ExpenseField typedSubtotal = index.firstByType(FIELD_SUBTOTAL);
        if (typedSubtotal != null) {
            claimed.add(typedSubtotal);
        }
        for (String keyword : SUBTOTAL_LABEL_KEYWORDS) {
            claimed.addAll(findAllFieldsByLabelContaining(index.allFields(), keyword));
        }
        return claimed;
    }

    private record CandidateAmount(BigDecimal amount, BigDecimal confidence) {
    }
}
