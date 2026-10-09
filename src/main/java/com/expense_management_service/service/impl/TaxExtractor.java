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
    /**
     * Labels that contain a tax keyword but whose value is not a tax amount: registration numbers
     * (a GSTIN such as 29ABCDE1234F1Z5 would otherwise parse as 2912341), HSN/SAC codes and rate columns.
     */
    private static final List<String> NON_AMOUNT_LABEL_KEYWORDS =
            List.of("GSTIN", "GST NO", "GST NUMBER", "GST REG", "VAT NO", "VAT REG", "TIN", "HSN", "SAC", "RATE");

    /** Indian GSTIN: 2-digit state code, 10-character PAN, entity digit, 'Z', check character. */
    private static final java.util.regex.Pattern GSTIN =
            java.util.regex.Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z\\d]Z[A-Z\\d]\\b");
    private static final List<String> COMPONENT_CODES = List.of("CGST", "SGST", "UTGST", "IGST", "CESS");

    /**
     * The receipt's tax components as printed (CGST 900 + SGST 900), summed per code. Empty when
     * the receipt only shows one tax figure. Same field filtering as {@link #extract}.
     */
    List<com.expense_management_service.dto.ocr.OcrTaxComponent> extractComponents(ExpenseFieldIndex index) {
        java.util.Map<String, BigDecimal> byCode = new java.util.LinkedHashMap<>();
        for (ExpenseField field : index.allFields()) {
            String label = labelOf(field);
            if (label == null || !isTaxAmountField(field)) {
                continue;
            }
            String normalizedLabel = label.toUpperCase(Locale.ROOT);
            // CESS only as a whole word ("PROCESSING FEE" contains it).
            String code = COMPONENT_CODES.stream()
                    .filter(c -> "CESS".equals(c) ? normalizedLabel.matches(".*\\bCESS\\b.*") : normalizedLabel.contains(c))
                    .findFirst().orElse(null);
            if (code == null) {
                continue;
            }
            BigDecimal amount = ReceiptFieldParsingUtils.parseAmount(textOf(field), label);
            if (amount != null) {
                byCode.merge(code, amount.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
            }
        }
        return byCode.entrySet().stream()
                .map(e -> new com.expense_management_service.dto.ocr.OcrTaxComponent(e.getKey(), e.getValue()))
                .toList();
    }

    /** First GSTIN found in a GSTIN-labelled field, else anywhere in the fields' text. */
    String extractGstin(ExpenseFieldIndex index) {
        String anywhere = null;
        for (ExpenseField field : index.allFields()) {
            String text = textOf(field);
            if (text == null) {
                continue;
            }
            java.util.regex.Matcher m = GSTIN.matcher(text.toUpperCase(Locale.ROOT).replaceAll("\\s", ""));
            if (!m.find()) {
                continue;
            }
            String label = labelOf(field);
            if (label != null && label.toUpperCase(Locale.ROOT).contains("GSTIN")) {
                return m.group();
            }
            if (anywhere == null) {
                anywhere = m.group();
            }
        }
        return anywhere;
    }

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
            if (!isGstComponent || !isTaxAmountField(field)) {
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
            if (!isTaxAmountField(field)) {
                continue;
            }
            BigDecimal amount = ReceiptFieldParsingUtils.parseAmount(textOf(field), labelKeyword);
            if (amount != null) {
                return ExtractionResult.of(amount, normalizedConfidenceOf(field));
            }
        }
        return ExtractionResult.empty();
    }

    /** False for identifier and rate fields (GSTIN, HSN, "CGST Rate", a value such as "9%") that only look like tax lines. */
    private static boolean isTaxAmountField(ExpenseField field) {
        String label = labelOf(field);
        // Whole-word match on a punctuation-free label, so "GSTIN:" and "GST No." match but "SGST" doesn't hit "GST NO".
        String wordLabel = " " + (label == null ? "" : label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim()) + " ";
        if (NON_AMOUNT_LABEL_KEYWORDS.stream().anyMatch(keyword -> wordLabel.contains(" " + keyword + " "))) {
            return false;
        }
        String text = textOf(field);
        return text == null || !text.contains("%");
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }
}
