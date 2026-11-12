package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ApPaymentEmailServiceTest {

    @Mock
    private EmailOutboxService emailOutbox;

    private ApPaymentEmailService service;

    @BeforeEach
    void setUp() {
        service = new ApPaymentEmailService(emailOutbox);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://portal.example.com/");
    }

    private static ExpenseReport report() {
        ExpenseReport report = new ExpenseReport();
        report.setReportNumber("EXP-2026-2027-OCTAP001");
        report.setTripStartDate(LocalDate.of(2026, 10, 3));
        report.setTripEndDate(LocalDate.of(2026, 10, 7));
        return report;
    }

    @Test
    void blankApAddress_sendsNothing() {
        ReflectionTestUtils.setField(service, "apAddress", "");

        service.onApprovedForPayment(report(), "Kandukoori Niharika", "INR 46.00");

        verify(emailOutbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void configuredApAddress_queuesPaymentDueMail_withReportAndAmount() {
        ReflectionTestUtils.setField(service, "apAddress", "ap@example.com");

        service.onApprovedForPayment(report(), "Kandukoori Niharika", "INR 46.00");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailOutbox).enqueue(eq("ap@example.com"), subject.capture(), body.capture(),
                eq(ApPaymentEmailService.EMAIL_AP_PAYMENT_DUE), eq("EXP-2026-2027-OCTAP001"));

        assertThat(subject.getValue())
                .contains("EXP-2026-2027-OCTAP001")
                .contains("Kandukoori Niharika")
                .contains("INR 46.00");
        assertThat(body.getValue())
                .contains("| Amount owed")
                .contains("INR 46.00")
                .contains("03 Oct 2026 to 07 Oct 2026")
                .contains("https://portal.example.com/expense-management/ap-payments/queue")
                .contains("not marked paid until the payment is recorded");
    }
}
