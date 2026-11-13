package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.CompletePaymentRequest;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentMethod;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.repository.ExpenseReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Milestone 1 / item 4: a payment can be recorded once. The report is read under a row lock, so a second confirmation
 * sees PAYMENT_COMPLETED and is refused before any budget, payment or audit write happens.
 */
@ExtendWith(MockitoExtension.class)
class ApPaymentDuplicateCompletionTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @InjectMocks private ApPaymentServiceImpl service;

    private static CompletePaymentRequest payment() {
        return new CompletePaymentRequest("UTR-123456", PaymentMethod.BANK_TRANSFER, LocalDate.now(), new BigDecimal("500.00"), null);
    }

    @Test
    void secondConfirmationOfAPaidReport_isRefused_andNothingIsWritten() {
        UUID id = UUID.randomUUID();
        ExpenseReport paid = ExpenseReport.builder()
                .reportId(id)
                .employeeId("EMP001")
                .reportNumber("ER-9")
                .paymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED)
                .reimbursableAmount(new BigDecimal("500.00"))
                .build();
        when(expenseReportRepository.findByIdForUpdate(id)).thenReturn(Optional.of(paid));

        assertThrows(IllegalArgumentException.class, () -> service.markPaymentCompleted(id, payment(), "AP001"));

        verify(expenseReportRepository, never()).save(any());
        verify(expenseReportRepository, never()).findById(any());
    }

    @Test
    void confirmation_readsTheReportUnderALock_notAPlainRead() {
        UUID id = UUID.randomUUID();
        when(expenseReportRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.markPaymentCompleted(id, payment(), "AP001"));

        verify(expenseReportRepository).findByIdForUpdate(id);
        verify(expenseReportRepository, never()).findById(any());
    }
}
