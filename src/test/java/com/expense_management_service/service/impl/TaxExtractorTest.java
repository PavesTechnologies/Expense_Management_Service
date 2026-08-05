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
    void extract_returnsEmpty_whenNoTaxInformationAnywhere() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Acme", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
