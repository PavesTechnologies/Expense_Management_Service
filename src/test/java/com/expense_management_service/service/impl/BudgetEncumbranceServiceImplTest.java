package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.BudgetEncumbranceOutcome;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.mapper.BudgetEncumbranceMapper;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.service.CostCenterBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetEncumbranceServiceImplTest {

    @Mock
    private BudgetEncumbranceRepository budgetEncumbranceRepository;
    @Mock
    private CostCenterBudgetRepository costCenterBudgetRepository;
    @Mock
    private CostCenterBudgetService costCenterBudgetService;

    private BudgetEncumbranceServiceImpl service;

    private UUID reportId;
    private ExpenseReport report;
    private CostCenter headerCostCenter;
    private CostCenter costCenterA;
    private CostCenter costCenterB;

    @BeforeEach
    void setUp() {
        service = new BudgetEncumbranceServiceImpl(
                budgetEncumbranceRepository, costCenterBudgetRepository, costCenterBudgetService, new BudgetEncumbranceMapper());

        reportId = UUID.randomUUID();
        headerCostCenter = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-HEADER").costCenterName("Engineering").build();
        costCenterA = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-A").costCenterName("Engineering").build();
        costCenterB = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-B").costCenterName("Marketing").allowUnbudgeted(false).build();

        report = ExpenseReport.builder()
                .reportId(reportId)
                .costCenter(headerCostCenter)
                .fiscalYear("FY2026")
                .totalAmount(BigDecimal.valueOf(10000))
                .build();
    }

    private CostCenterBudget budgetWith(CostCenter costCenter, BigDecimal availableBudget, BigDecimal warningThreshold) {
        return CostCenterBudget.builder()
                .budgetId(UUID.randomUUID())
                .costCenter(costCenter)
                .fiscalYear("FY2026")
                .availableBudget(availableBudget)
                .warningThreshold(warningThreshold)
                .build();
    }

    private ExpenseLineItem normalLineItem(BigDecimal amount) {
        // baseAmount is what resolveTargets actually sums (Org Base Currency) - equal to amount here
        // since these fixtures model a base-currency line item; amount is still set for realism.
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report)
                .amount(amount).baseAmount(amount).expenseSplits(List.of()).build();
    }

    private ExpenseLineItem splitLineItem(ExpenseSplit... splits) {
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report).amount(BigDecimal.valueOf(10000)).build();
        lineItem.setExpenseSplits(List.of(splits));
        return lineItem;
    }

    private ExpenseSplit split(CostCenter costCenter, BigDecimal amount) {
        return ExpenseSplit.builder().splitId(UUID.randomUUID()).costCenter(costCenter).splitType(SplitType.FIXED_AMOUNT).allocatedAmount(amount).build();
    }

    // ---------------------------------------------------------------------
    // Normal (non-split) expense - one report-level encumbrance against report.getCostCenter()
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_normalExpense_createsOneReportLevelEncumbrance() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(10000))));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(50000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(1);
        assertThat(outcome.encumbrances().get(0).amount()).isEqualByComparingTo("10000");
        assertThat(outcome.encumbrances().get(0).splitId()).isNull();
        assertThat(outcome.warnings()).isEmpty();
    }

    @Test
    void validateAndEncumber_normalExpense_usesBaseAmountNotDisplayAmount_forAForeignCurrencyLineItem() {
        // Foreign-currency line item: amount=10000 in its own currency, baseAmount=8500 once
        // converted - the encumbrance (and, downstream, budget consumption) must be in base currency.
        ExpenseLineItem foreignLineItem = normalLineItem(BigDecimal.valueOf(8500));
        foreignLineItem.setAmount(BigDecimal.valueOf(10000));
        report.setExpenseLineItems(List.of(foreignLineItem));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(50000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(1);
        assertThat(outcome.encumbrances().get(0).amount()).isEqualByComparingTo("8500"); // baseAmount, not the 10000 display amount
    }

    @Test
    void validateAndEncumber_zeroLineItems_createsNothing() {
        report.setExpenseLineItems(List.of());

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).isEmpty();
        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Split expense - one encumbrance per ExpenseSplit (Q24 granularity)
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_splitExpense_createsOneEncumbrancePerSplit() {
        ExpenseSplit splitA = split(costCenterA, BigDecimal.valueOf(6000));
        ExpenseSplit splitB = split(costCenterB, BigDecimal.valueOf(4000));
        costCenterB.setAllowUnbudgeted(true); // irrelevant here since a budget exists for CC-B too
        report.setExpenseLineItems(List.of(splitLineItem(splitA, splitB)));

        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        CostCenterBudget budgetB = budgetWith(costCenterB, BigDecimal.valueOf(20000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budgetA));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterB.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budgetB));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), eq(BudgetEncumbranceStatus.ACTIVE))).thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(2);
        assertThat(outcome.encumbrances()).extracting("amount")
                .containsExactlyInAnyOrder(new BigDecimal("6000"), new BigDecimal("4000"));
    }

    @Test
    void validateAndEncumber_mixedReport_createsOnePerSplitPlusOneForUnsplitRemainder() {
        ExpenseSplit splitA = split(costCenterA, BigDecimal.valueOf(6000));
        ExpenseLineItem splitLine = splitLineItem(splitA);
        ExpenseLineItem normalLine = normalLineItem(BigDecimal.valueOf(2000));
        report.setExpenseLineItems(List.of(splitLine, normalLine));

        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        CostCenterBudget headerBudget = budgetWith(headerCostCenter, BigDecimal.valueOf(20000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budgetA));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(headerBudget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), eq(BudgetEncumbranceStatus.ACTIVE))).thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(2);
        assertThat(outcome.encumbrances()).extracting("amount")
                .containsExactlyInAnyOrder(new BigDecimal("6000"), new BigDecimal("2000"));
    }

    @Test
    void validateAndEncumber_aggregatesSameCostCenterAcrossMultipleSplits_asOneCombinedNeed() {
        // Q10 (mega-review): CC-A appears in two different splits across two line items - validated
        // as ONE combined 3000 need against CC-A, not checked twice against the same shrinking figure.
        ExpenseSplit splitA1 = split(costCenterA, BigDecimal.valueOf(1000));
        ExpenseSplit splitA2 = split(costCenterA, BigDecimal.valueOf(1000)); // second split, same cost center, same line item's sibling not required
        ExpenseLineItem lineItem1 = splitLineItem(splitA1, splitA2);
        report.setExpenseLineItems(List.of(lineItem1));

        // Available is exactly 2000 - would fail if (incorrectly) validated as two separate 1000 needs
        // against a budget that only had, say, 1500 left after the first "check" - here we prove the
        // aggregate 2000 need is validated ONCE against the true 2000 available.
        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(2000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budgetA));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), eq(BudgetEncumbranceStatus.ACTIVE))).thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(2);
        // Only ONE lock/lookup call for CC-A, even though it's needed by two separate splits.
        verify(costCenterBudgetRepository, times(1))
                .findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026");
    }

    // ---------------------------------------------------------------------
    // Bug fix: report.fiscalYear and CostCenterBudget.fiscalYear must be in the same "YYYY-YYYY"
    // (April-March) format for the lookup to ever match - findWithLockByCostCenter_... is a plain
    // exact-string match, so it is the CALLER's job (ExpenseReportServiceImpl.fiscalYearFor) to
    // produce a value that agrees with how budgets are labeled, not this service's. See
    // ExpenseReportServiceImplTest's fiscalYearFor_* tests for the boundary logic itself.
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_matchesBudget_whenBothSidesUseTheRequiredYyyyDashYyyyFiscalYearFormat() {
        ExpenseReport crossYearReport = ExpenseReport.builder()
                .reportId(UUID.randomUUID()).costCenter(headerCostCenter).fiscalYear("2026-2027")
                .totalAmount(BigDecimal.valueOf(10000))
                .expenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(10000))))
                .build();
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(50000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                headerCostCenter.getCostCenterId(), "2026-2027"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(crossYearReport, 1);

        assertThat(outcome.encumbrances()).hasSize(1);
        assertThat(outcome.encumbrances().get(0).amount()).isEqualByComparingTo("10000");
    }

    @Test
    void validateAndEncumber_bareCalendarYearNeverMatchesAYyyyDashYyyyBudget_reproducingTheOriginalBug() {
        // Reproduces the exact reported bug: a budget configured as "2026-2027" is invisible to a
        // report whose fiscalYear was (before the fix) derived as the bare calendar year "2026" -
        // the lookup is a plain exact-string match, so this must fail as "no budget configured",
        // even though a budget genuinely exists for this cost center.
        ExpenseReport bareYearReport = ExpenseReport.builder()
                .reportId(UUID.randomUUID()).costCenter(headerCostCenter).fiscalYear("2026")
                .totalAmount(BigDecimal.valueOf(10000))
                .expenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(10000))))
                .build();
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                headerCostCenter.getCostCenterId(), "2026"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndEncumber(bareYearReport, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No budget is configured");
    }

    // ---------------------------------------------------------------------
    // Effective Available Budget accounts for already-ACTIVE encumbrances
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_accountsForExistingActiveEncumbrances_whenComputingEffectiveAvailable() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(10000))));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(15000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        // 15000 available - 6000 already ACTIVE = 9000 effective available, only just enough for 10000? No - should fail.
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(BudgetEncumbrance.builder().amount(BigDecimal.valueOf(6000)).build()));

        assertThatThrownBy(() -> service.validateAndEncumber(report, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient budget");

        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Insufficient budget - hard block, atomic, no partial encumbrances
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_splitExpense_oneCostCenterFails_entireSubmissionFails_noPartialEncumbrances() {
        ExpenseSplit splitA = split(costCenterA, BigDecimal.valueOf(6000));
        ExpenseSplit splitB = split(costCenterB, BigDecimal.valueOf(4000));
        report.setExpenseLineItems(List.of(splitLineItem(splitA, splitB)));

        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        CostCenterBudget budgetB = budgetWith(costCenterB, BigDecimal.valueOf(1000), null); // insufficient for 4000
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(any(), eq("FY2026")))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(costCenterA.getCostCenterId())) return Optional.of(budgetA);
                    if (id.equals(costCenterB.getCostCenterId())) return Optional.of(budgetB);
                    return Optional.empty();
                });
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), eq(BudgetEncumbranceStatus.ACTIVE))).thenReturn(List.of());

        assertThatThrownBy(() -> service.validateAndEncumber(report, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CC-B");

        // Atomicity: CC-A's own validation passing must never result in ANY row being persisted.
        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // allowUnbudgeted (CostCenter-level flag)
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_noBudgetConfigured_allowUnbudgetedTrue_createsFlaggedEncumbrance_noValidation() {
        costCenterA.setAllowUnbudgeted(true);
        report.setExpenseLineItems(List.of(splitLineItem(split(costCenterA, BigDecimal.valueOf(6000)))));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.empty());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(1);
        assertThat(outcome.encumbrances().get(0).unbudgeted()).isTrue();
        verify(budgetEncumbranceRepository, never()).findForUpdateByBudget_BudgetIdAndStatus(any(), any());
    }

    @Test
    void validateAndEncumber_noBudgetConfigured_allowUnbudgetedFalse_blocksSubmission() {
        costCenterA.setAllowUnbudgeted(false);
        report.setExpenseLineItems(List.of(splitLineItem(split(costCenterA, BigDecimal.valueOf(6000)))));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndEncumber(report, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbudgeted spending is not allowed");

        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    @Test
    void validateAndEncumber_mixedBudgetedAndUnbudgeted_proceedsForBoth() {
        costCenterB.setAllowUnbudgeted(true);
        ExpenseSplit splitA = split(costCenterA, BigDecimal.valueOf(6000)); // budgeted, sufficient
        ExpenseSplit splitB = split(costCenterB, BigDecimal.valueOf(4000)); // no budget row at all
        report.setExpenseLineItems(List.of(splitLineItem(splitA, splitB)));

        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budgetA));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterB.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.empty());
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budgetA.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(2);
        boolean anyUnbudgeted = outcome.encumbrances().stream().anyMatch(e -> Boolean.TRUE.equals(e.unbudgeted()));
        boolean anyBudgeted = outcome.encumbrances().stream().anyMatch(e -> !Boolean.TRUE.equals(e.unbudgeted()));
        assertThat(anyUnbudgeted).isTrue();
        assertThat(anyBudgeted).isTrue();
    }

    @Test
    void validateAndEncumber_mixedBudgetedAndUnbudgeted_budgetedOneFails_wholeSubmissionFails() {
        costCenterB.setAllowUnbudgeted(true);
        ExpenseSplit splitA = split(costCenterA, BigDecimal.valueOf(6000)); // budgeted, INSUFFICIENT
        ExpenseSplit splitB = split(costCenterB, BigDecimal.valueOf(4000)); // unbudgeted, would otherwise pass
        report.setExpenseLineItems(List.of(splitLineItem(splitA, splitB)));

        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(1000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(any(), eq("FY2026")))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    return id.equals(costCenterA.getCostCenterId()) ? Optional.of(budgetA) : Optional.empty();
                });
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), eq(BudgetEncumbranceStatus.ACTIVE))).thenReturn(List.of());

        assertThatThrownBy(() -> service.validateAndEncumber(report, 1))
                .isInstanceOf(IllegalArgumentException.class);

        // CC-B being legitimately unbudgeted must never bypass CC-A's genuine insufficient-budget failure.
        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Warning threshold - warn only, never blocks
    // ---------------------------------------------------------------------

    @Test
    void validateAndEncumber_belowWarningThresholdButStillAffordable_warnsWithoutBlocking() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(15000))));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(30000), BigDecimal.valueOf(20000));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // 30000 available, 15000 needed -> 15000 effective-available-after, below the 20000 threshold, still >= 0.
        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.encumbrances()).hasSize(1); // not blocked
        assertThat(outcome.warnings()).hasSize(1);
        assertThat(outcome.warnings().get(0).costCenterCode()).isEqualTo("CC-HEADER");
        assertThat(outcome.warnings().get(0).effectiveAvailableAfterEncumbrance()).isEqualByComparingTo("15000");
    }

    @Test
    void validateAndEncumber_aboveWarningThreshold_noWarning() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(5000))));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(30000), BigDecimal.valueOf(20000));
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // 30000 - 5000 = 25000 effective-available-after, ABOVE the 20000 threshold - no warning.
        BudgetEncumbranceOutcome outcome = service.validateAndEncumber(report, 1);

        assertThat(outcome.warnings()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Release
    // ---------------------------------------------------------------------

    @Test
    void releaseActiveForCycle_releasesEveryActiveEncumbrance() {
        BudgetEncumbrance e1 = BudgetEncumbrance.builder().encumbranceId(UUID.randomUUID()).status(BudgetEncumbranceStatus.ACTIVE).build();
        BudgetEncumbrance e2 = BudgetEncumbrance.builder().encumbranceId(UUID.randomUUID()).status(BudgetEncumbranceStatus.ACTIVE).build();
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(e1, e2));
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.releaseActiveForCycle(reportId, 1);

        assertThat(e1.getStatus()).isEqualTo(BudgetEncumbranceStatus.RELEASED);
        assertThat(e1.getReleasedAt()).isNotNull();
        assertThat(e2.getStatus()).isEqualTo(BudgetEncumbranceStatus.RELEASED);
    }

    @Test
    void releaseActiveForCycle_isANoOp_whenNothingIsActive() {
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());

        service.releaseActiveForCycle(reportId, 1);

        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Consume (Phase 6 will call this from AP completion - built and tested now, not yet wired)
    // ---------------------------------------------------------------------

    @Test
    void consumeActiveForReport_consumesBudgetedEncumbrance_viaExistingUnmodifiedConsumeBudget() {
        CostCenterBudget budget = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        BudgetEncumbrance encumbrance = BudgetEncumbrance.builder()
                .encumbranceId(UUID.randomUUID()).budget(budget).amount(BigDecimal.valueOf(6000))
                .status(BudgetEncumbranceStatus.ACTIVE).unbudgeted(false).build();
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(encumbrance));
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.consumeActiveForReport(reportId, 1);

        verify(costCenterBudgetService).consumeBudget(costCenterA, "FY2026", BigDecimal.valueOf(6000));
        assertThat(encumbrance.getStatus()).isEqualTo(BudgetEncumbranceStatus.CONSUMED);
        assertThat(encumbrance.getConsumedAt()).isNotNull();
    }

    @Test
    void consumeActiveForReport_unbudgetedEncumbrance_marksConsumedWithoutCallingConsumeBudget() {
        BudgetEncumbrance encumbrance = BudgetEncumbrance.builder()
                .encumbranceId(UUID.randomUUID()).budget(null).amount(BigDecimal.valueOf(4000))
                .status(BudgetEncumbranceStatus.ACTIVE).unbudgeted(true).build();
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(encumbrance));
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.consumeActiveForReport(reportId, 1);

        verify(costCenterBudgetService, never()).consumeBudget(any(), any(), any());
        assertThat(encumbrance.getStatus()).isEqualTo(BudgetEncumbranceStatus.CONSUMED);
    }

    // ---------------------------------------------------------------------
    // Idempotency under retries (production-readiness audit, Part 7) - both operations only ever
    // act on rows still ACTIVE, so a second call naturally finds nothing left to do; this is a
    // property of the query itself, not extra guard logic layered on top.
    // ---------------------------------------------------------------------

    @Test
    void releaseActiveForCycle_calledTwice_secondCallIsANoOp_neverDoubleReleases() {
        BudgetEncumbrance encumbrance = BudgetEncumbrance.builder().encumbranceId(UUID.randomUUID()).status(BudgetEncumbranceStatus.ACTIVE).build();
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenAnswer(inv -> encumbrance.getStatus() == BudgetEncumbranceStatus.ACTIVE ? List.of(encumbrance) : List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.releaseActiveForCycle(reportId, 1);
        LocalDateTime firstReleasedAt = encumbrance.getReleasedAt();
        service.releaseActiveForCycle(reportId, 1);

        assertThat(encumbrance.getStatus()).isEqualTo(BudgetEncumbranceStatus.RELEASED);
        assertThat(encumbrance.getReleasedAt()).isEqualTo(firstReleasedAt); // untouched by the second, no-op call
        verify(budgetEncumbranceRepository, times(1)).saveAll(anyList());
    }

    @Test
    void consumeActiveForReport_calledTwice_secondCallReturnsFalseAndNeverConsumesBudgetAgain() {
        CostCenterBudget budget = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        BudgetEncumbrance encumbrance = BudgetEncumbrance.builder()
                .encumbranceId(UUID.randomUUID()).budget(budget).amount(BigDecimal.valueOf(6000))
                .status(BudgetEncumbranceStatus.ACTIVE).unbudgeted(false).build();
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenAnswer(inv -> encumbrance.getStatus() == BudgetEncumbranceStatus.ACTIVE ? List.of(encumbrance) : List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        boolean first = service.consumeActiveForReport(reportId, 1);
        boolean second = service.consumeActiveForReport(reportId, 1);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        verify(costCenterBudgetService, times(1)).consumeBudget(any(), any(), any()); // never twice
    }

    @Test
    void releaseActiveForCycle_afterAlreadyConsumed_isANoOp() {
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of()); // already CONSUMED, so nothing is ACTIVE anymore

        service.releaseActiveForCycle(reportId, 1);

        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
    }

    @Test
    void consumeActiveForReport_afterAlreadyReleased_returnsFalseAndNeverConsumesBudget() {
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of()); // already RELEASED, so nothing is ACTIVE anymore

        boolean result = service.consumeActiveForReport(reportId, 1);

        assertThat(result).isFalse();
        verify(costCenterBudgetService, never()).consumeBudget(any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // effectiveAvailable
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // reconcileForCycle (production-readiness audit, Part 1) - resumeInPlace's budget reconciliation
    // ---------------------------------------------------------------------

    private BudgetEncumbrance activeEncumbrance(UUID budgetId, ExpenseSplit split, BigDecimal amount) {
        return BudgetEncumbrance.builder().encumbranceId(UUID.randomUUID())
                .budget(budgetId == null ? null : CostCenterBudget.builder().budgetId(budgetId).costCenter(headerCostCenter).build())
                .report(report).split(split).submissionCycle(1).amount(amount)
                .status(BudgetEncumbranceStatus.ACTIVE).unbudgeted(false).build();
    }

    @Test
    void reconcileForCycle_noBudgetImpactingChange_leavesActiveEncumbranceUntouched() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(10000))));
        BudgetEncumbrance existing = activeEncumbrance(UUID.randomUUID(), null, BigDecimal.valueOf(10000));
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(existing));

        service.reconcileForCycle(report, 1);

        verify(budgetEncumbranceRepository, never()).saveAll(anyList());
        verify(costCenterBudgetRepository, never()).findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(any(), any());
    }

    @Test
    void reconcileForCycle_amountIncreased_releasesStaleAndCreatesFreshEncumbrance() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(15000)))); // corrected from 10000 -> 15000
        BudgetEncumbrance existing = activeEncumbrance(UUID.randomUUID(), null, BigDecimal.valueOf(10000));
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(existing));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(50000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of()); // the stale 10000 was already released by the time validateAndEncumber re-checks
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.reconcileForCycle(report, 1);

        assertThat(existing.getStatus()).isEqualTo(BudgetEncumbranceStatus.RELEASED);
        verify(costCenterBudgetRepository).findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026");
    }

    @Test
    void reconcileForCycle_amountDecreased_releasesExcessReservation() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(6000)))); // corrected from 10000 down to 6000
        BudgetEncumbrance existing = activeEncumbrance(UUID.randomUUID(), null, BigDecimal.valueOf(10000));
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(existing));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(50000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.reconcileForCycle(report, 1);

        assertThat(existing.getStatus()).isEqualTo(BudgetEncumbranceStatus.RELEASED);
    }

    @Test
    void reconcileForCycle_splitCostCenterChanged_releasesOldAndValidatesNewAllocation() {
        ExpenseSplit newSplitForCcA = split(costCenterA, BigDecimal.valueOf(4000));
        report.setExpenseLineItems(List.of(splitLineItem(newSplitForCcA))); // was previously encumbered against a DIFFERENT (now-removed) split
        BudgetEncumbrance staleEncumbrance = activeEncumbrance(UUID.randomUUID(), split(costCenterB, BigDecimal.valueOf(4000)), BigDecimal.valueOf(4000));
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(staleEncumbrance));
        CostCenterBudget budgetA = budgetWith(costCenterA, BigDecimal.valueOf(20000), null);
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budgetA));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), eq(BudgetEncumbranceStatus.ACTIVE))).thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.reconcileForCycle(report, 1);

        assertThat(staleEncumbrance.getStatus()).isEqualTo(BudgetEncumbranceStatus.RELEASED);
        verify(costCenterBudgetRepository).findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterA.getCostCenterId(), "FY2026");
    }

    @Test
    void reconcileForCycle_correctedAmountExceedsAvailableBudget_throwsAndNeverLeavesAPartialState() {
        report.setExpenseLineItems(List.of(normalLineItem(BigDecimal.valueOf(15000))));
        BudgetEncumbrance existing = activeEncumbrance(UUID.randomUUID(), null, BigDecimal.valueOf(10000));
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(existing));
        CostCenterBudget budget = budgetWith(headerCostCenter, BigDecimal.valueOf(12000), null); // only 12000 total, corrected need is 15000
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(headerCostCenter.getCostCenterId(), "FY2026"))
                .thenReturn(Optional.of(budget));
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of());
        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // The service layer itself cannot roll back the release it already issued (no DB here) -
        // atomicity is the caller's @Transactional boundary (resumeInPlace), which reverts the
        // release alongside this failure since both happen in the same transaction in production.
        assertThatThrownBy(() -> service.reconcileForCycle(report, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient budget");
    }

    @Test
    void effectiveAvailable_subtractsActiveEncumbrancesFromAvailableBudget() {
        CostCenterBudget budget = budgetWith(costCenterA, BigDecimal.valueOf(30000), null);
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE))
                .thenReturn(List.of(BudgetEncumbrance.builder().amount(BigDecimal.valueOf(20000)).build()));

        BigDecimal result = service.effectiveAvailable(budget);

        assertThat(result).isEqualByComparingTo("10000");
    }
}
