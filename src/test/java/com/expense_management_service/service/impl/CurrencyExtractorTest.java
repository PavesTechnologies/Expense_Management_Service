package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.util.List;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.lineBlock;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class CurrencyExtractorTest {

    private final CurrencyExtractor extractor = new CurrencyExtractor();

    @Test
    void extract_prefersStructuredCurrencyField_overTextScan() {
        ExpenseFieldIndex index = indexOf(typedField("TOTAL", "$123.45", 90.0f, "EUR"));

        assertThat(extractor.extract(index).value()).isEqualTo("EUR");
    }

    @ParameterizedTest
    @CsvSource({
            "'₹837.90', INR",
            "'INR 837.90', INR",
            "'Rs. 837.90', INR",
            "'837.90 Rupees', INR",
            "'$123.45', USD",
            "'€99.00', EUR",
            "'£50.00', GBP",
            "'AED 200.00', AED",
            "'SAR 300.00', SAR",
            "'¥1000', JPY"
    })
    void extract_detectsCurrency_fromSymbolOrKeyword_whenNoStructuredCurrencyField(String totalText, String expectedCurrency) {
        ExpenseFieldIndex index = indexOf(typedField("TOTAL", totalText, 90.0f, null));

        assertThat(extractor.extract(index).value()).isEqualTo(expectedCurrency);
    }

    /** Task 13: raw OCR fallback when no field's text carries a currency symbol/keyword at all. */
    @Test
    void extract_fallsBackToRawOcrLine_whenNoFieldHasCurrencyInformation() {
        ExpenseFieldIndex index = indexOf(List.<ExpenseField>of(typedField("TOTAL", "123.45", 90.0f, null)),
                List.of(lineBlock("Amount in INR", 0.05f)));

        assertThat(extractor.extract(index).value()).isEqualTo("INR");
    }

    @Test
    void extract_returnsEmpty_whenNoCurrencySignalAnywhere() {
        ExpenseFieldIndex index = indexOf(typedField("TOTAL", "123.45", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
