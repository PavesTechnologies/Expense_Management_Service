package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Consumers that make money decisions must follow the RECORDS, not the cached column. Each test sets a stale cached value
 * and checks that the decision uses the calculation from the records, including that reversed offsets are excluded.
 */
@ExtendWith(MockitoExtension.class)
class BalanceConsumersUseCalculatorTest {

    @Mock private CashAdvanceAdjustmentRepository adjustmentRepository;
    @Mock private CashAdvanceRepaymentRepository repaymentRepository;
    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private CashAdvanceBudgetMovements budgetMovements;
    @Mock private StorageService storageService;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;

    private CashAdvanceBalanceCalculator calculator;
    private UUID id;
    private CashAdvance advance;

    private void setUp(String stored) {
        calculator = new CashAdvanceBalanceCalculator(adjustmentRepository, repaymentRepository);
        id = UUID.randomUUID();
        advance = CashAdvance.builder()
                .advanceId(id)
                .employeeId("EMP001")
                .amount(new BigDecimal("1000.00"))
                .outstandingBalance(new BigDecimal(stored))   // deliberately stale cache
                .status("SETTLEMENT_PENDING")
                .currency(Currency.builder().currencyCode("INR").decimalPlaces(2).build())
                .build();
    }

    private static CashAdvanceRepayment repayment(CashAdvance a, String amount) {
        return CashAdvanceRepayment.builder().cashAdvance(a).amount(new BigDecimal(amount)).build();
    }

    @Test
    void payrollCeiling_usesTheRecords_notTheStaleCache() {
        setUp("1000.00");                                     // cache says 1000
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(repayment(advance, "600.00")));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());
        CashAdvancePayrollPosting posting = new CashAdvancePayrollPosting(cashAdvanceRepository, repaymentRepository,
                budgetMovements, mock(CashAdvanceBalanceLedger.class), storageService, calculator);

        assertEquals(0, new BigDecimal("400.00").compareTo(posting.currentBalance(advance)), "records say 400, not the cached 1000");
        // A deduction of 500 is above what the records support, so it is refused even though the cache would allow it.
        assertThrows(BusinessRuleViolationException.class, () -> posting.post(advance, new BigDecimal("500.00"), "REF", null, null, "AP", "test"));
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void payrollCeiling_excludesReversedOffsets() {
        setUp("1000.00");
        CashAdvanceAdjustment active = CashAdvanceAdjustment.builder().cashAdvance(advance).adjustedAmount(new BigDecimal("300.00")).reversed(false).build();
        CashAdvanceAdjustment reversed = CashAdvanceAdjustment.builder().cashAdvance(advance).adjustedAmount(new BigDecimal("500.00")).reversed(true).build();
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(active, reversed));
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());
        CashAdvancePayrollPosting posting = new CashAdvancePayrollPosting(cashAdvanceRepository, repaymentRepository,
                budgetMovements, mock(CashAdvanceBalanceLedger.class), storageService, calculator);

        assertEquals(0, new BigDecimal("700.00").compareTo(posting.currentBalance(advance)), "1000 - active 300; reversed 500 ignored");
    }

    @Test
    void statusRefresh_closesTheAdvance_whenTheRecordsSayNothingIsLeft_eventThoughTheCacheDisagrees() {
        setUp("400.00");                                      // cache says 400 is still owed
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(repayment(advance, "1000.00")));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(id)).thenReturn(List.of());
        CashAdvanceBalanceLedger ledger = new CashAdvanceBalanceLedger(auditLogRepository, calculator, expenseReportRepository);

        ledger.refreshStatus(advance, "SYSTEM", "test");

        assertEquals(0, BigDecimal.ZERO.compareTo(advance.getOutstandingBalance()), "the cache is brought into line with the records");
        assertEquals("CLOSED", advance.getStatus());
    }

    @Test
    void calculator_neverHidesANegativeFromDecisions() {
        setUp("0.00");
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(
                CashAdvanceAdjustment.builder().cashAdvance(advance).adjustedAmount(new BigDecimal("1200.00")).reversed(false).build()));
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());

        assertThrows(BusinessRuleViolationException.class, () -> calculator.outstanding(advance),
                "a decision must not run on a negative balance");
    }
}
