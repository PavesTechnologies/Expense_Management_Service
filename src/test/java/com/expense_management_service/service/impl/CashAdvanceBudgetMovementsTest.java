package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for moving money between a cash advance's allocation and the budget. Database-backed
 * concurrency (two transactions on the same budget row) needs MySQL and is not covered here.
 */
@ExtendWith(MockitoExtension.class)
class CashAdvanceBudgetMovementsTest {

    private static final String FY = "2026-2027";

    @Mock private CostCenterBudgetRepository costCenterBudgetRepository;

    private CashAdvanceBudgetMovements movements;
    private CostCenter costCenter;
    private CashAdvance advance;
    private CostCenterBudget budget;

    @BeforeEach
    void setUp() {
        movements = new CashAdvanceBudgetMovements(costCenterBudgetRepository);
        costCenter = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("ENG").build();
        advance = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .amount(new BigDecimal("5000.00"))
                .costCenter(costCenter)
                .budgetFiscalYear(FY)
                .build();
        // Pool 10,000; principal 5,000 charged at approval: available 5,000, allocated 5,000
        budget = CostCenterBudget.builder()
                .budgetId(UUID.randomUUID())
                .fiscalYear(FY)
                .budgetAmount(new BigDecimal("10000.00"))
                .availableBudget(new BigDecimal("5000.00"))
                .cashAdvanceAllocated(new BigDecimal("5000.00"))
                .cashAdvanceFeeAllocated(BigDecimal.ZERO)
                .build();
    }

    private void stubLock() {
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                eq(costCenter.getCostCenterId()), eq(FY))).thenReturn(Optional.of(budget));
    }

    @Test
    void offset_movesAllocationIntoUtilization_andLeavesAvailableUnchanged() {
        stubLock();

        movements.moveAllocationToUtilized(advance, new BigDecimal("3000.00"));

        assertEquals(0, new BigDecimal("2000.00").compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(budget.getAvailableBudget()));
        // Actual utilization = pool − available − allocated − fees = 10,000 − 5,000 − 2,000 − 0 = 3,000
        assertEquals(0, new BigDecimal("3000.00").compareTo(
                budget.getBudgetAmount().subtract(budget.getAvailableBudget())
                        .subtract(budget.getCashAdvanceAllocated()).subtract(budget.getCashAdvanceFeeAllocated())));
        verify(costCenterBudgetRepository).save(budget);
    }

    @Test
    void repayment_releasesUnusedPrincipal_backToAvailable() {
        stubLock();

        movements.releaseAllocation(advance, new BigDecimal("2000.00"));

        assertEquals(0, new BigDecimal("3000.00").compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, new BigDecimal("7000.00").compareTo(budget.getAvailableBudget()));
        verify(costCenterBudgetRepository).save(budget);
    }

    @Test
    void fullOffsetThenRepayment_leavesTheExpenseChargedOnce() {
        // The worked example: advance 5,000; expenses 3,000 offset; 2,000 repaid
        stubLock();

        movements.moveAllocationToUtilized(advance, new BigDecimal("3000.00"));
        movements.releaseAllocation(advance, new BigDecimal("2000.00"));

        assertEquals(0, BigDecimal.ZERO.compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, new BigDecimal("7000.00").compareTo(budget.getAvailableBudget()));
        // Only the 3,000 of real expenses is utilized: 10,000 − 7,000 − 0 − 0
        assertEquals(0, new BigDecimal("3000.00").compareTo(
                budget.getBudgetAmount().subtract(budget.getAvailableBudget())));
    }

    @Test
    void offsetLargerThanTheAllocation_isRejected_andNothingIsSaved() {
        stubLock();

        assertThrows(BusinessRuleViolationException.class,
                () -> movements.moveAllocationToUtilized(advance, new BigDecimal("5000.01")));

        assertEquals(0, new BigDecimal("5000.00").compareTo(budget.getCashAdvanceAllocated()));
        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void repaymentLargerThanTheAllocation_isRejected_andNothingIsSaved() {
        stubLock();

        assertThrows(BusinessRuleViolationException.class,
                () -> movements.releaseAllocation(advance, new BigDecimal("6000.00")));

        assertEquals(0, new BigDecimal("5000.00").compareTo(budget.getAvailableBudget()));
        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void deletedOffset_isReversed_backIntoAllocation() {
        stubLock();

        movements.reverseOffset(advance, new BigDecimal("3000.00"));

        assertEquals(0, new BigDecimal("8000.00").compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(budget.getAvailableBudget()));
    }

    @Test
    void advanceNeverChargedToABudget_isIgnored_withoutTouchingTheBudget() {
        advance.setBudgetFiscalYear(null);

        movements.moveAllocationToUtilized(advance, new BigDecimal("3000.00"));
        movements.releaseAllocation(advance, new BigDecimal("2000.00"));
        movements.reverseOffset(advance, new BigDecimal("1000.00"));

        verify(costCenterBudgetRepository, never()).findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(any(), any());
        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void zeroAmount_isIgnored() {
        movements.moveAllocationToUtilized(advance, BigDecimal.ZERO);
        movements.releaseAllocation(advance, null);

        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void missingBudgetRow_isAnError_notASilentSkip() {
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                eq(costCenter.getCostCenterId()), eq(FY))).thenReturn(Optional.empty());

        assertThrows(BusinessRuleViolationException.class,
                () -> movements.moveAllocationToUtilized(advance, new BigDecimal("1.00")));
    }
}
