package com.expense_management_service.service.impl;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A plain TOTAL printed as a label, as on the METRO MART and fuel bills: "TOTAL  Rs. 357.00". */
class AmountPlainTotalLabelTest {

    private final AmountExtractor extractor = new AmountExtractor();

    @Test
    void plainTotalLabelIsReadWhenTextractDidNotTypeIt() {
        // Subtotal Rs. 340.00 + tax Rs. 17.00 = TOTAL Rs. 357.00
        ExpenseFieldIndex index = indexOf(
                labeledField("TOTAL", "Rs. 357.00", 90.0f));

        ExtractionResult<BigDecimal> result = extractor.extractTotal(index, new BigDecimal("340.00"), new BigDecimal("17.00"));

        assertThat(result.value()).isEqualByComparingTo("357.00");
    }

    @Test
    void subtotalLabelIsNeverReadAsTheTotal() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Subtotal", "Rs. 340.00", 90.0f),
                labeledField("SUB TOTAL", "Rs. 340.00", 90.0f));

        assertThat(extractor.extractTotal(index, null, null).value()).isNull();
    }
}
