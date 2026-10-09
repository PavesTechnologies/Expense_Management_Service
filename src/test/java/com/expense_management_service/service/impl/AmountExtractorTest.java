package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class AmountExtractorTest {

    private final AmountExtractor extractor = new AmountExtractor();

    @Test
    void extractSubtotal_usesStandardSubtotalType() {
        ExpenseFieldIndex index = indexOf(typedField("SUBTOTAL", "798.00", 90.0f, null));

        assertThat(extractor.extractSubtotal(index).value()).isEqualByComparingTo("798.00");
    }

    @Test
    void extractSubtotal_fallsBackToTaxableAmountLabel() {
        ExpenseFieldIndex index = indexOf(labeledField("Taxable Amount", "500.00", 90.0f));

        assertThat(extractor.extractSubtotal(index).value()).isEqualByComparingTo("500.00");
    }

    /**
     * The exact reported scenario: Subtotal=798.00, CGST=19.95, SGST=19.95, and the final payable
     * amount printed under "UN-PAID" rather than the standard TOTAL type. Reconciliation
     * (798 + 39.90 = 837.90) must win over a differently-shaped "Total" field that merely repeats
     * the subtotal.
     */
    @Test
    void extractTotal_reconciliationWins_overATotalFieldThatJustRepeatsTheSubtotal() {
        ExpenseFieldIndex index = indexOf(
                typedField("TOTAL", "798.00", 90.0f, null),
                labeledField("UN-PAID", "837.90", 90.0f));

        ExtractionResult<BigDecimal> result = extractor.extractTotal(index, new BigDecimal("798.00"), new BigDecimal("39.90"));

        assertThat(result.value()).isEqualByComparingTo("837.90");
    }

    @Test
    void extractTotal_grandTotalOutranksStandardTotalType_whenReconciliationCannotDecide() {
        ExpenseFieldIndex index = indexOf(
                typedField("TOTAL", "100.00", 90.0f, null),
                labeledField("Grand Total", "110.00", 90.0f));

        // No subtotal/tax known — reconciliation can't decide, so the explicit priority order applies.
        ExtractionResult<BigDecimal> result = extractor.extractTotal(index, null, null);

        assertThat(result.value()).isEqualByComparingTo("110.00");
    }

    @Test
    void extractTotal_amountDueOutranksNetAmount_whenReconciliationCannotDecide() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Net Amount", "50.00", 90.0f),
                labeledField("Amount Due", "60.00", 90.0f));

        ExtractionResult<BigDecimal> result = extractor.extractTotal(index, null, null);

        assertThat(result.value()).isEqualByComparingTo("60.00");
    }

    @Test
    void extractTotal_neverUsesSubtotal_evenWhenGrandTotalIsAbsent_becauseSubtotalIsNeverACandidateAtAll() {
        ExpenseFieldIndex index = indexOf(typedField("SUBTOTAL", "798.00", 90.0f, null));

        ExtractionResult<BigDecimal> result = extractor.extractTotal(index, new BigDecimal("798.00"), null);

        assertThat(result.isPresent()).isFalse();
    }

    /** Ensures the Total tier's "NET AMOUNT" keyword never swallows the Subtotal tier's "Net Amount Before Tax" field. */
    @Test
    void extractTotal_doesNotConfuseNetAmountBeforeTax_withTheNetAmountTotalTier() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Net Amount Before Tax", "500.00", 90.0f),
                labeledField("Grand Total", "590.00", 90.0f));

        assertThat(extractor.extractTotal(index, null, null).value()).isEqualByComparingTo("590.00");
        assertThat(extractor.extractSubtotal(index).value()).isEqualByComparingTo("500.00");
    }

    @Test
    void extractTotal_returnsEmpty_whenNoTotalShapedFieldExistsAtAll() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Acme", 90.0f, null));

        assertThat(extractor.extractTotal(index, null, null).isPresent()).isFalse();
    }
}
