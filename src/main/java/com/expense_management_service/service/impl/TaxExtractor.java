package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.findAllFieldsByLabelContaining;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.labelOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizedConfidenceOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 5: extracts the receipt's tax amount, trying — in order — Indian GST components
 * (CGST/SGST/IGST, summed when more than one is present, since a receipt never has just one
 * component that represents the whole tax), a generic "GST" label, "VAT", "Service Tax", and
 * finally Textract's standard TAX ExpenseType. Each tier is only tried once the previous one
 * found nothing, so a receipt with real CGST+SGST fields never falls through to a spurious
 * generic-GST or TAX match.
 */
final class TaxExtractor {

    private static final String FIELD_TAX = "TAX";
    private static final List<String> GST_COMPONENT_LABEL_KEYWORDS = List.of("CGST", "SGST", "IGST");

    ExtractionResult<BigDecimal> extract(ExpenseFieldIndex index) {
        ExtractionResult<BigDecimal> gstComponentSum = sumGstComponents(index.allFields());
        if (gstComponentSum.isPresent()) {
            return gstComponentSum;
        }

        ExtractionResult<BigDecimal> genericGst = firstLabeledAmount(index.allFields(), "GST");
        if (genericGst.isPresent()) {
            return genericGst;
        }

        ExtractionResult<BigDecimal> vat = firstLabeledAmount(index.allFields(), "VAT");
        if (vat.isPresent()) {
            return vat;
        }

        ExtractionResult<BigDecimal> serviceTax = firstLabeledAmount(index.allFields(), "SERVICE TAX");
        if (serviceTax.isPresent()) {
            return serviceTax;
        }

        ExpenseField taxField = index.firstByType(FIELD_TAX);
        BigDecimal taxAmount = ReceiptFieldParsingUtils.parseAmount(textOf(taxField), FIELD_TAX);
        return ExtractionResult.of(taxAmount, normalizedConfidenceOf(taxField));
    }

    /** Indian GST receipts report CGST/SGST/IGST as separate label-matched OTHER-type fields rather than a single TAX line — their sum is the true tax, never just one component. */
    private ExtractionResult<BigDecimal> sumGstComponents(List<ExpenseField> allFields) {
        BigDecimal sum = null;
        List<BigDecimal> componentConfidences = new ArrayList<>();

        for (ExpenseField field : allFields) {
            String label = labelOf(field);
            if (label == null) {
                continue;
            }
            String normalizedLabel = label.toUpperCase(Locale.ROOT);
            boolean isGstComponent = GST_COMPONENT_LABEL_KEYWORDS.stream().anyMatch(normalizedLabel::contains);
            if (!isGstComponent) {
                continue;
            }
            BigDecimal componentAmount = ReceiptFieldParsingUtils.parseAmount(textOf(field), label);
            if (componentAmount != null) {
                sum = sum == null ? componentAmount : sum.add(componentAmount);
                BigDecimal confidence = normalizedConfidenceOf(field);
                if (confidence != null) {
                    componentConfidences.add(confidence);
                }
            }
        }

        if (sum == null) {
            return ExtractionResult.empty();
        }
        BigDecimal averageConfidence = componentConfidences.isEmpty() ? null : average(componentConfidences);
        return ExtractionResult.of(sum.setScale(4, RoundingMode.HALF_UP), averageConfidence);
    }

    private ExtractionResult<BigDecimal> firstLabeledAmount(List<ExpenseField> allFields, String labelKeyword) {
        List<ExpenseField> matches = findAllFieldsByLabelContaining(allFields, labelKeyword);
        for (ExpenseField field : matches) {
            BigDecimal amount = ReceiptFieldParsingUtils.parseAmount(textOf(field), labelKeyword);
            if (amount != null) {
                return ExtractionResult.of(amount, normalizedConfidenceOf(field));
            }
        }
        return ExtractionResult.empty();
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }
}
