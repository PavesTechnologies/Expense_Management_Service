package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.time.LocalDate;
import java.util.List;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.lineBlock;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class DateExtractorTest {

    private final DateExtractor extractor = new DateExtractor();

    @ParameterizedTest
    @ValueSource(strings = {"05/06/26", "05/06/2026", "2026-06-05", "05-Jun-2026", "5 Jun 2026", "05-06-2026"})
    void extract_parsesEveryDocumentedDateFormat(String dateText) {
        ExpenseFieldIndex index = indexOf(typedField("INVOICE_RECEIPT_DATE", dateText, 90.0f, null));

        ExtractionResult<LocalDate> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    void extract_triesEveryFieldOfTheSameType_whenTheFirstOneIsUnparseable() {
        ExpenseFieldIndex index = indexOf(
                typedField("INVOICE_RECEIPT_DATE", "not-a-date", 90.0f, null),
                typedField("INVOICE_RECEIPT_DATE", "05/06/2026", 85.0f, null)
        );

        assertThat(extractor.extract(index).value()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    void extract_fallsBackToLabelScan_whenNoStandardDateTypePresent() {
        ExpenseFieldIndex index = indexOf(labeledField("Travel Date", "2026-06-05", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    void extract_prefersInvoiceDateLabel_overGenericDateLabel() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Printed Date", "01/01/2026", 90.0f),
                labeledField("Invoice Date", "05/06/2026", 90.0f)
        );

        assertThat(extractor.extract(index).value()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    /** Task 13: raw OCR fallback when no structured or labeled date field exists at all. */
    @Test
    void extract_fallsBackToRawOcrLine_whenNoStructuredOrLabeledDateExists() {
        ExpenseFieldIndex index = indexOf(List.<ExpenseField>of(), List.of(
                lineBlock("Some unrelated line", 0.05f),
                lineBlock("Date: 05/06/2026", 0.10f)
        ));

        assertThat(extractor.extract(index).value()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    void extract_returnsEmpty_whenDateFormatIsUnrecognizedEverywhere() {
        ExpenseFieldIndex index = indexOf(typedField("INVOICE_RECEIPT_DATE", "not-a-date", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
