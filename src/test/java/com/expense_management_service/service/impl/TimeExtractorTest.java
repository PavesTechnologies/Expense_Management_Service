package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.time.LocalTime;
import java.util.List;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.lineBlock;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class TimeExtractorTest {

    private final TimeExtractor extractor = new TimeExtractor();

    @ParameterizedTest
    @ValueSource(strings = {"13:52", "01:52 PM", "13:52:08"})
    void extract_parsesEveryDocumentedTimeFormat_fromATypedTimeField(String timeText) {
        ExpenseFieldIndex index = indexOf(typedField("RECEIPT_TIME", timeText, 90.0f, null));

        ExtractionResult<LocalTime> result = extractor.extract(index);

        assertThat(result.value().getHour()).isEqualTo(13);
        assertThat(result.value().getMinute()).isEqualTo(52);
    }

    @Test
    void extract_findsTimeEmbeddedInDateFieldText_beforeCheckingTypedTimeField() {
        ExpenseFieldIndex index = indexOf(typedField("INVOICE_RECEIPT_DATE", "05/06/2026 13:52:08", 90.0f, null));

        assertThat(extractor.extract(index).value()).isEqualTo(LocalTime.of(13, 52, 8));
    }

    @Test
    void extract_fallsBackToLabelScan_whenNoStandardTimeTypePresent() {
        ExpenseFieldIndex index = indexOf(labeledField("Departure Time", "01:52 PM", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo(LocalTime.of(13, 52));
    }

    /** Task 13: raw OCR fallback when no structured or labeled time field exists at all. */
    @Test
    void extract_fallsBackToRawOcrLine_whenNoStructuredOrLabeledTimeExists() {
        ExpenseFieldIndex index = indexOf(List.<ExpenseField>of(), List.of(lineBlock("Time of visit: 13:52:08", 0.05f)));

        assertThat(extractor.extract(index).value()).isEqualTo(LocalTime.of(13, 52, 8));
    }

    @Test
    void extract_returnsEmpty_whenNoTimeInformationAvailableAnywhere() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Acme", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
