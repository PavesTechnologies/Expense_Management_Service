package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.util.List;
import java.util.function.Predicate;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.labelOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizeForLabelMatching;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizedConfidenceOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 8: extracts an invoice/bill/receipt reference number. Textract's standard
 * {@code INVOICE_RECEIPT_ID} ExpenseType is tried first; a label scan follows, in priority order
 * Invoice No/Invoice Number, Bill No, Tax Invoice, Receipt No, Ticket Number, and finally
 * Reference Number (explicitly lowest-priority per Task 8).
 * <p>
 * Tier matching uses explicit predicates rather than plain "label contains keyword" substring
 * checks: a naive "contains INVOICE" check would also match "Tax Invoice Number" (its normalized
 * text literally contains "INVOICENUMBER" as a substring), incorrectly promoting a Tax Invoice
 * field into the plain-Invoice tier. Each predicate below is written to exclude the case the next
 * tier down is meant to own.
 */
final class InvoiceNumberExtractor {

    private static final String FIELD_INVOICE_RECEIPT_ID = "INVOICE_RECEIPT_ID";

    private static final List<Predicate<String>> LABEL_TIER_PREDICATES = List.of(
            label -> label.contains("INVOICE") && !label.contains("TAX"),
            label -> label.contains("BILLNO") || label.contains("BILLNUMBER"),
            label -> label.contains("TAX") && label.contains("INVOICE"),
            label -> label.contains("RECEIPTNO") || label.contains("RECEIPTNUMBER"),
            label -> label.contains("TICKETNO") || label.contains("TICKETNUMBER"),
            label -> label.contains("REFERENCENO") || label.contains("REFERENCENUMBER")
    );

    ExtractionResult<String> extract(ExpenseFieldIndex index) {
        ExpenseField typed = index.firstByType(FIELD_INVOICE_RECEIPT_ID);
        String typedText = textOf(typed);
        if (typedText != null) {
            return ExtractionResult.of(typedText, normalizedConfidenceOf(typed));
        }

        for (Predicate<String> tierPredicate : LABEL_TIER_PREDICATES) {
            for (ExpenseField field : index.allFields()) {
                String label = labelOf(field);
                if (label == null || !tierPredicate.test(normalizeForLabelMatching(label))) {
                    continue;
                }
                String text = textOf(field);
                if (text != null) {
                    return ExtractionResult.of(text, normalizedConfidenceOf(field));
                }
            }
        }
        return ExtractionResult.empty();
    }
}
