package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.PaymentProofFields;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProofExtractorTest {

    @Test
    void readsTheUpiFieldsFromAGooglePaySuccessScreen() {
        PaymentProofFields fields = PaymentProofExtractor.parse(List.of(
                "Payment successful",
                "Paid to merchant@okbank",
                "₹ 4,163.00",
                "27 Oct 2026, 10:14 AM",
                "UPI transaction ID 431234567890"));

        assertThat(fields.reference()).isEqualTo("431234567890");
        assertThat(fields.payeeUpiId()).isEqualTo("merchant@okbank");
        assertThat(fields.amount()).isEqualByComparingTo(new BigDecimal("4163.00"));
        assertThat(fields.transferDate()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    void readsNumericDatesAndBankReferences() {
        PaymentProofFields fields = PaymentProofExtractor.parse(List.of(
                "NEFT UTR: HDFC26102712345",
                "Date 28/10/2026",
                "Amount INR 1,200"));

        assertThat(fields.reference()).isEqualTo("HDFC26102712345");
        assertThat(fields.transferDate()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(fields.amount()).isEqualByComparingTo(new BigDecimal("1200"));
    }

    @Test
    void leavesFieldsEmptyWhenTheScreenshotDoesNotShowThem() {
        PaymentProofFields fields = PaymentProofExtractor.parse(List.of("Thank you"));

        assertThat(fields.reference()).isNull();
        assertThat(fields.transferDate()).isNull();
        assertThat(fields.payeeUpiId()).isNull();
        assertThat(fields.amount()).isNull();
    }

    @Test
    void fallsBackToATwelveDigitNumberWhenNoLabelIsGiven() {
        assertThat(PaymentProofExtractor.parse(List.of("Ref 987654321012")).reference()).isEqualTo("987654321012");
    }
}
