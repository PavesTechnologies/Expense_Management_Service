package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for applying a verified expense report against its cash advance. The database-backed
 * behaviour (transaction rollback on failure, concurrent offsets) needs MySQL and is not covered here.
 */
@ExtendWith(MockitoExtension.class)
class CashAdvanceOffsetServiceTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    @Mock private CashAdvanceAdjustmentService cashAdvanceAdjustmentService;
    @Mock private CurrentUserService currentUserService;

    private CashAdvanceOffsetService service;
    private CashAdvance advance;
    private ExpenseReport report;
    private UUID reportId;

    @BeforeEach
    void setUp() {
        service = new CashAdvanceOffsetService(expenseReportRepository, cashAdvanceAdjustmentRepository,
                cashAdvanceAdjustmentService, currentUserService);

        Currency inr = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("INR").decimalPlaces(2).build();
        advance = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId("5100014")
                .amount(new BigDecimal("15000.00"))
                .outstandingBalance(new BigDecimal("15000.00"))
                .status("SETTLEMENT_PENDING")
                .build();

        reportId = UUID.randomUUID();
        report = ExpenseReport.builder()
                .reportId(reportId)
                .reportNumber("EXP-2026-2027-AAAAAAAA")
                .reportStatus(ReportStatus.APPROVED)
                .totalAmount(new BigDecimal("3000.00"))
                .currency(inr)
                .cashAdvance(advance)
                .build();

        lenient().when(currentUserService.getEmployeeId()).thenReturn("5100023");
        lenient().when(cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId()))
                .thenReturn(List.of());
        lenient().when(cashAdvanceAdjustmentService.create(any(CashAdvanceAdjustmentRequest.class)))
                .thenReturn(org.mockito.Mockito.mock(CashAdvanceAdjustmentResponse.class));
    }

    private void stubReport() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
    }

    @Test
    void verifiedReport_withoutAmount_offsetsTheVerifiedTotal() {
        stubReport();

        service.applyOffset(reportId, null, null);

        ArgumentCaptor<CashAdvanceAdjustmentRequest> captor = ArgumentCaptor.forClass(CashAdvanceAdjustmentRequest.class);
        verify(cashAdvanceAdjustmentService).create(captor.capture());
        assertEquals(advance.getAdvanceId(), captor.getValue().advanceId());
        assertEquals(reportId, captor.getValue().reportId());
        assertEquals(0, new BigDecimal("3000.00").compareTo(captor.getValue().adjustedAmount()));
        assertEquals("5100023", captor.getValue().adjustedBy());
    }

    @Test
    void outstandingBelowVerifiedTotal_capsTheDefaultAtTheOutstandingBalance() {
        advance.setOutstandingBalance(new BigDecimal("2000.00"));
        stubReport();

        service.applyOffset(reportId, null, null);

        ArgumentCaptor<CashAdvanceAdjustmentRequest> captor = ArgumentCaptor.forClass(CashAdvanceAdjustmentRequest.class);
        verify(cashAdvanceAdjustmentService).create(captor.capture());
        assertEquals(0, new BigDecimal("2000.00").compareTo(captor.getValue().adjustedAmount()));
    }

    @Test
    void requestedAmountAboveVerifiedTotal_isRejected() {
        stubReport();

        assertThrows(BusinessRuleViolationException.class,
                () -> service.applyOffset(reportId, null, new BigDecimal("3000.01")));
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }

    @Test
    void requestedAmountAboveOutstanding_isRejected() {
        advance.setOutstandingBalance(new BigDecimal("1000.00"));
        stubReport();

        assertThrows(BusinessRuleViolationException.class,
                () -> service.applyOffset(reportId, null, new BigDecimal("1500.00")));
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }

    @Test
    void reportNotLinkedToAnAdvance_isRejected() {
        report.setCashAdvance(null);
        stubReport();

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.applyOffset(reportId, null, null));
        assertEquals("This expense report is not linked to a cash advance.", ex.getMessage());
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }

    @Test
    void reportAlreadyOffsetAgainstTheAdvance_isRejected_notAppliedTwice() {
        CashAdvanceAdjustment existing = CashAdvanceAdjustment.builder().report(report).cashAdvance(advance).build();
        when(cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId()))
                .thenReturn(List.of(existing));
        stubReport();

        assertThrows(BusinessRuleViolationException.class, () -> service.applyOffset(reportId, null, null));
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }

    @Test
    void reportNotVerified_isRejected() {
        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        stubReport();

        assertThrows(BusinessRuleViolationException.class, () -> service.applyOffset(reportId, null, null));
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }

    @Test
    void advanceNamedInRequest_mustMatchTheLinkedAdvance() {
        stubReport();

        assertThrows(BusinessRuleViolationException.class,
                () -> service.applyOffset(reportId, UUID.randomUUID(), null));
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }

    @Test
    void zeroAmount_isRejected() {
        stubReport();

        assertThrows(BusinessRuleViolationException.class,
                () -> service.applyOffset(reportId, null, BigDecimal.ZERO));
        verify(cashAdvanceAdjustmentService, never()).create(any());
    }
}
