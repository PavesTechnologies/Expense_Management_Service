package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class TaxExtractorTest {

    private final TaxExtractor extractor = new TaxExtractor();

    @Test
    void extract_sumsCgstAndSgst_whenBothPresent() {
        ExpenseFieldIndex index = indexOf(
                labeledField("CGST", "14.50", 90.0f),
                labeledField("SGST", "14.50", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("29.00");
    }

    @Test
    void extract_sumsAllThreeGstComponents_whenCgstSgstAndIgstPresent() {
        ExpenseFieldIndex index = indexOf(
                labeledField("CGST", "9.00", 90.0f),
                labeledField("SGST", "9.00", 90.0f),
                labeledField("IGST", "5.00", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("23.00");
    }

    @Test
    void extract_usesGenericGstLabel_whenNoGstComponentsPresent() {
        ExpenseFieldIndex index = indexOf(labeledField("GST", "18.00", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("18.00");
    }

    @Test
    void extract_usesVatLabel_whenNoGstAtAll() {
        ExpenseFieldIndex index = indexOf(labeledField("VAT", "12.50", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("12.50");
    }

    @Test
    void extract_usesServiceTaxLabel_whenNoGstOrVat() {
        ExpenseFieldIndex index = indexOf(labeledField("Service Tax", "7.25", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("7.25");
    }

    @Test
    void extract_fallsBackToStandardTaxType_whenNothingElseMatches() {
        ExpenseFieldIndex index = indexOf(typedField("TAX", "10.00", 90.0f, null));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("10.00");
    }

    @Test
    void extract_prefersGstComponentSum_overGenericTaxTypeField() {
        ExpenseFieldIndex index = indexOf(
                labeledField("CGST", "9.00", 90.0f),
                labeledField("SGST", "9.00", 90.0f),
                typedField("TAX", "999.00", 90.0f, null));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("18.00");
    }

    @Test
    void extract_ignoresGstinRegistrationNumber() {
        // "29ABCDE1234F1Z5" would parse as 2912341 if treated as an amount.
        ExpenseFieldIndex index = indexOf(
                labeledField("GSTIN:", "29ABCDE1234F1Z5", 95.0f),
                labeledField("GST", "18.00", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("18.00");
    }

    @Test
    void extract_ignoresGstComponentRateFields_andSumsOnlyTheAmounts() {
        ExpenseFieldIndex index = indexOf(
                labeledField("CGST Rate", "9", 90.0f),
                labeledField("CGST", "9%", 90.0f),
                labeledField("CGST @ 9%", "45.00", 90.0f),
                labeledField("SGST @ 9%", "45.00", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualByComparingTo("90.00");
    }

    @Test
    void extract_returnsEmpty_whenOnlyAGstinIsPresent() {
        ExpenseFieldIndex index = indexOf(labeledField("GSTIN/UIN", "27AAPFU0939F1ZV", 95.0f));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }

    @Test
    void extract_returnsEmpty_whenNoTaxInformationAnywhere() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Acme", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }

    @Test
    void extractComponents_keepsEachComponent_andIgnoresRateAndGstinFields() {
        ExpenseFieldIndex index = indexOf(
                labeledField("GSTIN", "29ABCDE1234F1Z5", 95.0f),
                labeledField("CGST Rate", "9", 90.0f),
                labeledField("CGST @ 9%", "900.00", 90.0f),
                labeledField("SGST @ 9%", "900.00", 90.0f),
                labeledField("Compensation Cess", "120.00", 90.0f),
                labeledField("Processing fee", "50.00", 90.0f));

        assertThat(extractor.extractComponents(index))
                .extracting(com.expense_management_service.dto.ocr.OcrTaxComponent::code)
                .containsExactly("CGST", "SGST", "CESS");
        assertThat(extractor.extractComponents(index).get(0).amount()).isEqualByComparingTo("900.00");
    }

    @Test
    void extractGstin_prefersTheGstinLabelledField() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Bill to", "Buyer 27AAPFU0939F1ZV", 90.0f),
                labeledField("GSTIN:", "29ABCDE1234F1Z5", 95.0f));

        assertThat(extractor.extractGstin(index)).isEqualTo("29ABCDE1234F1Z5");
    }

    @Test
    void extractGstin_returnsNull_whenNonePrinted() {
        assertThat(extractor.extractGstin(indexOf(labeledField("GST", "18.00", 90.0f)))).isNull();
    }
}
