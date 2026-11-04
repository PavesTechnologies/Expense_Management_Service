package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadlineReminderServiceTest {

    private static final String EMPLOYEE = "5100021";

    @Mock
    private CashAdvanceRepository cashAdvanceRepository;

    @Mock
    private EmployeeCacheRepository employeeCacheRepository;

    @Mock
    private EmailOutboxService emailOutbox;

    private DeadlineReminderService service;

    @BeforeEach
    void setUp() {
        service = new DeadlineReminderService(cashAdvanceRepository, employeeCacheRepository, emailOutbox);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://portal.example.com/");
    }

    // Trip ends 2026-10-20, so the expense deadline is 2026-11-19 and the reminder is due from 2026-11-12.
    private static CashAdvance advance() {
        return CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId(EMPLOYEE)
                .title("Hyderabad client visit")
                .amount(new BigDecimal("1000.00"))
                .status("IN_PROGRESS")
                .expectedTripEndDate(LocalDate.of(2026, 10, 20))
                .build();
    }

    private void employeeHasMail(String mail) {
        when(employeeCacheRepository.findByEmployeeId(EMPLOYEE)).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId(EMPLOYEE).firstName("Niharika").lastName("Kandukoori")
                        .workEmail(mail).build()));
    }

    @Test
    void dueAdvance_queuesOneReminder_andRecordsTheSend() {
        CashAdvance advance = advance();
        when(cashAdvanceRepository.findByStatusInAndDeadlineReminderSentAtIsNull(anyCollection())).thenReturn(List.of(advance));
        employeeHasMail("niharika@example.com");
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 8, 0);

        int queued = service.sendDueReminders(now);

        assertThat(queued).isEqualTo(1);
        verify(emailOutbox).enqueue(eq("niharika@example.com"), anyString(), anyString(),
                eq(DeadlineReminderService.EMAIL_DEADLINE_REMINDER), eq(String.valueOf(advance.getAdvanceId())));
        assertThat(advance.getDeadlineReminderSentAt()).isEqualTo(now);
        verify(cashAdvanceRepository).save(advance);
    }

    @Test
    void mailBody_namesTheEmployeeDeadlineAndLink() {
        when(cashAdvanceRepository.findByStatusInAndDeadlineReminderSentAtIsNull(anyCollection())).thenReturn(List.of(advance()));
        employeeHasMail("niharika@example.com");

        service.sendDueReminders(LocalDateTime.of(2026, 11, 13, 8, 0));

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailOutbox).enqueue(anyString(), anyString(), body.capture(), anyString(), anyString());
        assertThat(body.getValue())
                .contains("Dear Niharika,")
                .contains("Expense deadline: 19 Nov 2026")
                .contains("https://portal.example.com/expense-management/cash-advance/my");
    }

    @Test
    void notYetDue_queuesNothing() {
        when(cashAdvanceRepository.findByStatusInAndDeadlineReminderSentAtIsNull(anyCollection())).thenReturn(List.of(advance()));

        int queued = service.sendDueReminders(LocalDateTime.of(2026, 11, 1, 8, 0));

        assertThat(queued).isZero();
        verify(emailOutbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noWorkEmail_queuesNothing_andLeavesTheReminderOpen() {
        CashAdvance advance = advance();
        when(cashAdvanceRepository.findByStatusInAndDeadlineReminderSentAtIsNull(anyCollection())).thenReturn(List.of(advance));
        employeeHasMail("");

        int queued = service.sendDueReminders(LocalDateTime.of(2026, 11, 13, 8, 0));

        assertThat(queued).isZero();
        assertThat(advance.getDeadlineReminderSentAt()).isNull();
        verify(emailOutbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cashAdvanceRepository, never()).save(any());
    }
}
