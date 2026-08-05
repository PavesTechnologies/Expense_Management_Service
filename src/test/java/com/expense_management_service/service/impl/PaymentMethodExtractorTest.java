package com.expense_management_service.service.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PaymentMethodExtractorTest {

    private final PaymentMethodExtractor extractor = new PaymentMethodExtractor();

    @ParameterizedTest
    @CsvSource({
            "Cash, Cash",
            "Credit Card, Credit Card",
            "Debit Card, Debit Card",
            "UPI, UPI",
            "Google Pay, Google Pay",
            "PhonePe, PhonePe",
            "Paytm, Paytm",
            "Visa, Visa",
            "MasterCard, MasterCard",
            "Amex, Amex",
            "American Express, Amex"
    })
    void extract_recognizesEveryDocumentedPaymentMethod(String rawText, String expectedCanonicalName) {
        ExpenseFieldIndex index = indexOf(labeledField("Payment Method", rawText, 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo(expectedCanonicalName);
    }

    @Test
    void extract_prefersBrandName_overGenericUpi_whenBothMentioned() {
        ExpenseFieldIndex index = indexOf(labeledField("Payment Method", "UPI (Google Pay)", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("Google Pay");
    }

    @Test
    void extract_doesNotReturnRawUnrecognizedText() {
        ExpenseFieldIndex index = indexOf(labeledField("Payment Method", "Store Credit Voucher #4471", 90.0f));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }

    @Test
    void extract_scansOtherFields_whenNoDedicatedPaymentLabelExists() {
        ExpenseFieldIndex index = indexOf(labeledField("Notes", "Paid by PhonePe", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("PhonePe");
    }

    @Test
    void extract_returnsEmpty_whenNoPaymentMethodMentionedAnywhere() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Acme", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
