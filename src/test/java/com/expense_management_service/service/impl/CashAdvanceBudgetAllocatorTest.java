package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceFee;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.CashAdvanceFeeRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cash advance budget allocation. These cover arithmetic, currency conversion,
 * validation, the duplicate-fee short-circuit, and config errors, without a database.
 *
 * Concurrency (the pessimistic lock actually serialising two transactions) and transaction rollback
 * need a real MySQL database, which this project's test setup does not provide (there is no H2
 * dependency). They are NOT covered by this class and still need an integration test against MySQL.
 */
@ExtendWith(MockitoExtension.class)
class CashAdvanceBudgetAllocatorTest {

    private static final String FISCAL_YEAR = "2026-2027";
    private static final String BASE_CODE = "INR";

    @Mock private CostCenterBudgetRepository costCenterBudgetRepository;
    @Mock private CashAdvanceFeeRepository cashAdvanceFeeRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private ExchangeRateService exchangeRateService;

    private CashAdvanceBudgetAllocator allocator;
    private CostCenter costCenter;
    private Currency base;
    private Currency aed;
    private CashAdvance advance;
    private CostCenterBudget budget;

    @BeforeEach
    void setUp() {
        allocator = new CashAdvanceBudgetAllocator(
                costCenterBudgetRepository, cashAdvanceFeeRepository, expenseReportRepository,
                expenseLineItemRepository, expenseCategoryRepository, systemConfigurationRepository,
                currencyRepository, exchangeRateService);
        ReflectionTestUtils.setField(allocator, "baseCurrencyCode", BASE_CODE);

        base = Currency.builder().currencyId(UUID.randomUUID()).currencyCode(BASE_CODE).decimalPlaces(2).build();
        aed = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("AED").decimalPlaces(2).build();
        lenient().when(currencyRepository.findByCurrencyCodeIgnoreCase(BASE_CODE)).thenReturn(Optional.of(base));

        costCenter = CostCenter.builder()
                .costCenterId(UUID.randomUUID())
                .costCenterCode("ENG")
                .costCenterName("Engineering")
                .allowUnbudgeted(false)
                .build();

        // Default advance is already in the base currency, so no conversion is involved
        advance = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId("5100014")
                .amount(new BigDecimal("5000.00"))
                .baseAmount(new BigDecimal("5000.00"))
                .currency(base)
                .costCenter(costCenter)
                .build();

        budget = CostCenterBudget.builder()
                .budgetId(UUID.randomUUID())
                .fiscalYear(FISCAL_YEAR)
                .budgetAmount(new BigDecimal("10000.00"))
                .availableBudget(new BigDecimal("10000.00"))
                .build();
    }

    private void stubBudgetLock() {
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                costCenter.getCostCenterId(), FISCAL_YEAR)).thenReturn(Optional.of(budget));
    }

    private void stubFeeRate(String rate) {
        when(systemConfigurationRepository.findByConfigKey(CashAdvanceBudgetAllocator.FEE_RATE_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder()
                        .configKey(CashAdvanceBudgetAllocator.FEE_RATE_CONFIG_KEY)
                        .configValue(rate)
                        .build()));
    }

    private void stubFeeCategory() {
        when(systemConfigurationRepository.findByConfigKey(CashAdvanceBudgetAllocator.FEE_CATEGORY_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder()
                        .configKey(CashAdvanceBudgetAllocator.FEE_CATEGORY_CONFIG_KEY)
                        .configValue("CASH_ADVANCE_FEE")
                        .build()));
        when(expenseCategoryRepository.findFirstByCategoryCodeIgnoreCase("CASH_ADVANCE_FEE"))
                .thenReturn(Optional.of(ExpenseCategory.builder().categoryCode("CASH_ADVANCE_FEE").build()));
    }

    // Lenient: not every scenario reaches every save (a zero fee creates no report), and that is not a test failure
    private void passThroughSaves() {
        lenient().when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(costCenterBudgetRepository.save(any(CostCenterBudget.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approvalWithinBudget_deductsPrincipalAndFeeOnce_andRecordsCompanyPaidFee() {
        stubBudgetLock();
        stubFeeRate("0.02");
        stubFeeCategory();
        passThroughSaves();

        allocator.allocateOnFinalApproval(advance, FISCAL_YEAR);

        // principal 5000 + fee 100 (2% of 5000) = 5100 deducted once from 10000
        assertEquals(0, new BigDecimal("4900.00").compareTo(budget.getAvailableBudget()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, new BigDecimal("100.00").compareTo(budget.getCashAdvanceFeeAllocated()));

        ArgumentCaptor<ExpenseReport> report = ArgumentCaptor.forClass(ExpenseReport.class);
        verify(expenseReportRepository).save(report.capture());
        assertTrue(report.getValue().isCompanyPaid());
        assertEquals(ReportStatus.CLOSED, report.getValue().getReportStatus());
        assertEquals(PaymentRoutingStatus.NONE, report.getValue().getPaymentRoutingStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(report.getValue().getReimbursableAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(report.getValue().getTotalAmount()));
        assertEquals(base, report.getValue().getCurrency());

        ArgumentCaptor<ExpenseLineItem> line = ArgumentCaptor.forClass(ExpenseLineItem.class);
        verify(expenseLineItemRepository).save(line.capture());
        assertEquals(0, new BigDecimal("100.00").compareTo(line.getValue().getAmount()));
        assertEquals("SYSTEM", line.getValue().getCreatedBy());

        ArgumentCaptor<CashAdvanceFee> fee = ArgumentCaptor.forClass(CashAdvanceFee.class);
        verify(cashAdvanceFeeRepository).save(fee.capture());
        assertEquals(0, new BigDecimal("100.00").compareTo(fee.getValue().getAmount()));
        assertEquals(0, new BigDecimal("0.020000").compareTo(fee.getValue().getFeeRate()));
        assertEquals(report.getValue(), fee.getValue().getExpenseReport());
    }

    @Test
    void foreignCurrencyAdvance_isConvertedToBaseBeforeTheBudgetIsCharged() {
        // AED 1,000 at 20 INR per AED = INR 20,000 principal; fee 2% of that = INR 400
        advance.setCurrency(aed);
        advance.setAmount(new BigDecimal("1000.00"));
        advance.setBaseAmount(new BigDecimal("1000.00")); // the stored copy of the local amount, which must be ignored
        budget.setAvailableBudget(new BigDecimal("25000.00"));
        stubBudgetLock();
        stubFeeRate("0.02");
        stubFeeCategory();
        passThroughSaves();
        when(exchangeRateService.convertAmount(eq(new BigDecimal("1000.00")), eq(aed.getCurrencyId()),
                eq(base.getCurrencyId()), any(LocalDate.class))).thenReturn(new BigDecimal("20000.00"));

        allocator.allocateOnFinalApproval(advance, FISCAL_YEAR);

        assertEquals(0, new BigDecimal("4600.00").compareTo(budget.getAvailableBudget()));
        assertEquals(0, new BigDecimal("20000.00").compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, new BigDecimal("400.00").compareTo(budget.getCashAdvanceFeeAllocated()));
        ArgumentCaptor<CashAdvanceFee> fee = ArgumentCaptor.forClass(CashAdvanceFee.class);
        verify(cashAdvanceFeeRepository).save(fee.capture());
        assertEquals(0, new BigDecimal("400.00").compareTo(fee.getValue().getAmount()));
    }

    @Test
    void foreignCurrencyAdvance_withoutAnExchangeRate_failsAndWritesNothing() {
        advance.setCurrency(aed);
        stubBudgetLock();
        when(exchangeRateService.convertAmount(any(), any(), any(), any(LocalDate.class)))
                .thenThrow(new ResourceNotFoundException("No exchange rate is available"));

        assertThrows(ResourceNotFoundException.class,
                () -> allocator.allocateOnFinalApproval(advance, FISCAL_YEAR));

        assertEquals(0, new BigDecimal("10000.00").compareTo(budget.getAvailableBudget()));
        verify(costCenterBudgetRepository, never()).save(any());
        verify(cashAdvanceFeeRepository, never()).save(any());
    }

    @Test
    void baseCurrencyNotConfiguredAsCurrency_fails() {
        when(currencyRepository.findByCurrencyCodeIgnoreCase(BASE_CODE)).thenReturn(Optional.empty());
        stubBudgetLock();

        assertThrows(BusinessRuleViolationException.class,
                () -> allocator.allocateOnFinalApproval(advance, FISCAL_YEAR));
        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void insufficientBudget_throwsAndWritesNothing() {
        budget.setAvailableBudget(new BigDecimal("5050.00")); // principal 5000 + fee 100 = 5100 > 5050
        stubBudgetLock();
        stubFeeRate("0.02");

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> allocator.allocateOnFinalApproval(advance, FISCAL_YEAR));

        assertTrue(ex.getMessage().contains("exceeds the remaining Cost Center budget"));
        verify(costCenterBudgetRepository, never()).save(any());
        verify(expenseReportRepository, never()).save(any());
        verify(cashAdvanceFeeRepository, never()).save(any());
        assertEquals(0, new BigDecimal("5050.00").compareTo(budget.getAvailableBudget()));
        assertNull(budget.getCashAdvanceAllocated());
    }

    @Test
    void principalAloneExactlyMatchingAvailable_isAllowed_whenFeeIsZero() {
        budget.setAvailableBudget(new BigDecimal("5000.00"));
        stubBudgetLock();
        stubFeeRate("0");
        passThroughSaves();

        allocator.allocateOnFinalApproval(advance, FISCAL_YEAR);

        assertEquals(0, BigDecimal.ZERO.compareTo(budget.getAvailableBudget()));
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void noFeeRateConfigured_feeIsZero_noReport_butFeeRowStillRecordedForIdempotency() {
        stubBudgetLock();
        when(systemConfigurationRepository.findByConfigKey(CashAdvanceBudgetAllocator.FEE_RATE_CONFIG_KEY))
                .thenReturn(Optional.empty());
        passThroughSaves();

        allocator.allocateOnFinalApproval(advance, FISCAL_YEAR);

        assertEquals(0, new BigDecimal("5000.00").compareTo(budget.getCashAdvanceAllocated()));
        assertEquals(0, BigDecimal.ZERO.compareTo(budget.getCashAdvanceFeeAllocated()));
        verify(expenseReportRepository, never()).save(any());
        ArgumentCaptor<CashAdvanceFee> fee = ArgumentCaptor.forClass(CashAdvanceFee.class);
        verify(cashAdvanceFeeRepository).save(fee.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(fee.getValue().getAmount()));
        assertNull(fee.getValue().getExpenseReport());
    }

    @Test
    void duplicateApproval_feeAlreadyRecorded_isANoOp() {
        stubBudgetLock();
        when(cashAdvanceFeeRepository.existsByAdvance_AdvanceId(advance.getAdvanceId())).thenReturn(true);

        allocator.allocateOnFinalApproval(advance, FISCAL_YEAR);

        assertEquals(0, new BigDecimal("10000.00").compareTo(budget.getAvailableBudget()));
        assertNull(budget.getCashAdvanceAllocated());
        verify(costCenterBudgetRepository, never()).save(any());
        verify(cashAdvanceFeeRepository, never()).save(any());
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void lockIsTakenBeforeTheDuplicateCheck() {
        stubBudgetLock();
        when(systemConfigurationRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        passThroughSaves();

        allocator.allocateOnFinalApproval(advance, FISCAL_YEAR);

        InOrder order = inOrder(costCenterBudgetRepository, cashAdvanceFeeRepository);
        order.verify(costCenterBudgetRepository).findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                costCenter.getCostCenterId(), FISCAL_YEAR);
        order.verify(cashAdvanceFeeRepository).existsByAdvance_AdvanceId(advance.getAdvanceId());
    }

    @Test
    void feeCategoryMissing_throwsBeforeAnyWrite() {
        stubBudgetLock();
        stubFeeRate("0.02");
        when(systemConfigurationRepository.findByConfigKey(CashAdvanceBudgetAllocator.FEE_CATEGORY_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder()
                        .configKey(CashAdvanceBudgetAllocator.FEE_CATEGORY_CONFIG_KEY)
                        .configValue("CASH_ADVANCE_FEE").build()));
        when(expenseCategoryRepository.findFirstByCategoryCodeIgnoreCase("CASH_ADVANCE_FEE"))
                .thenReturn(Optional.empty());

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> allocator.allocateOnFinalApproval(advance, FISCAL_YEAR));

        assertTrue(ex.getMessage().contains("does not exist"));
        // The approval transaction rolls back on this exception, so no report or fee may be saved first
        verify(expenseReportRepository, never()).save(any());
        verify(cashAdvanceFeeRepository, never()).save(any());
    }

    @Test
    void invalidFeeRate_isAnErrorNotASilentZero() {
        stubBudgetLock();
        stubFeeRate("two percent");

        assertThrows(BusinessRuleViolationException.class,
                () -> allocator.allocateOnFinalApproval(advance, FISCAL_YEAR));
        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void feeRateOutOfRange_isRejected() {
        assertThrows(BusinessRuleViolationException.class, () -> CashAdvanceBudgetAllocator.parseRate("2"));
        assertThrows(BusinessRuleViolationException.class, () -> CashAdvanceBudgetAllocator.parseRate("-0.01"));
        assertEquals(0, new BigDecimal("0.020000").compareTo(CashAdvanceBudgetAllocator.parseRate("0.02")));
    }

    @Test
    void noBudgetRowAndNotUnbudgeted_throws() {
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                costCenter.getCostCenterId(), FISCAL_YEAR)).thenReturn(Optional.empty());
        when(cashAdvanceFeeRepository.existsByAdvance_AdvanceId(advance.getAdvanceId())).thenReturn(false);

        assertThrows(BusinessRuleViolationException.class,
                () -> allocator.allocateOnFinalApproval(advance, FISCAL_YEAR));
        verify(cashAdvanceFeeRepository, never()).save(any());
    }
}
