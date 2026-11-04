package com.expense_management_service.service.impl;

import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.repository.EmployeeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalReminderEmailServiceTest {

    @Mock
    private EmailOutboxService emailOutbox;

    @Mock
    private EmployeeCacheRepository employeeCacheRepository;

    private ApprovalReminderEmailService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalReminderEmailService(emailOutbox, employeeCacheRepository);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://portal.example.com/");
    }

    private static ExpenseReport report() {
        ExpenseReport report = new ExpenseReport();
        report.setReportNumber("EXP-2026-2027-OCTAP001");
        return report;
    }

    @Test
    void remind_queuesTheMailToTheApproversWorkEmail() {
        when(employeeCacheRepository.findByEmployeeId("5100023")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100023").workEmail("rama@example.com").build()));

        service.remind("5100023", report(), "Kandukoori Niharika", "INR 1,046.00");

        verify(emailOutbox).enqueue(eq("rama@example.com"),
                eq("Reminder: EXP-2026-2027-OCTAP001 is overdue for your approval"),
                anyString(),
                eq(ApprovalReminderEmailService.EMAIL_APPROVAL_REMINDER),
                eq("EXP-2026-2027-OCTAP001"));
    }

    @Test
    void remind_withoutAWorkEmail_queuesNothing() {
        when(employeeCacheRepository.findByEmployeeId("5100023")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100023").workEmail(" ").build()));

        service.remind("5100023", report(), "Kandukoori Niharika", "INR 1,046.00");

        verify(emailOutbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
