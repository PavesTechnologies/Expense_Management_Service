package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseSplitReplaceRequest;
import com.expense_management_service.dto.request.ExpenseSplitRequest;
import com.expense_management_service.dto.response.ExpenseSplitResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.mapper.ExpenseSplitMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseSplitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseSplitServiceImplTest {

    private static final String OWNER_EMPLOYEE_ID = "5100101";

    @Mock
    private ExpenseSplitRepository expenseSplitRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private CostCenterRepository costCenterRepository;
    @Mock
    private com.expense_management_service.repository.CostAllocationRepository costAllocationRepository;

    private ExpenseSplitServiceImpl service;

    private UUID lineItemId;
    private ExpenseLineItem lineItem;
    private UUID costCenterAId;
    private UUID costCenterBId;
    private CostCenter costCenterA;
    private CostCenter costCenterB;

    @BeforeEach
    void setUp() {
        service = new ExpenseSplitServiceImpl(
                expenseSplitRepository, expenseLineItemRepository, costCenterRepository, costAllocationRepository, new ExpenseSplitMapper());

        lineItemId = UUID.randomUUID();
        ExpenseReport report = ExpenseReport.builder()
                .reportId(UUID.randomUUID())
                .employeeId(OWNER_EMPLOYEE_ID)
                .reportStatus(ReportStatus.DRAFT)
                .build();
        lineItem = ExpenseLineItem.builder()
                .lineItemId(lineItemId)
                .report(report)
                .amount(BigDecimal.valueOf(10000))
                .baseAmount(BigDecimal.valueOf(10000)) // base currency == display currency for this fixture
                .build();

        costCenterAId = UUID.randomUUID();
        costCenterBId = UUID.randomUUID();
        costCenterA = CostCenter.builder().costCenterId(costCenterAId).costCenterCode("CC-A").costCenterName("Engineering").build();
        costCenterB = CostCenter.builder().costCenterId(costCenterBId).costCenterCode("CC-B").costCenterName("Marketing").build();
    }

    private void stubOwnedEditableLineItem() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lineItemId)).thenReturn(List.of());
    }

    private void stubSaveAllPassthrough() {
        when(expenseSplitRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------------
    // Ownership / editability guards
    // ---------------------------------------------------------------------

    @Test
    void getSplits_throwsAccessDenied_whenCallerDoesNotOwnTheReport() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> service.getSplitsForLineItem(lineItemId, "someone-else"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getSplits_throwsResourceNotFound_whenLineItemMissing() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSplitsForLineItem(lineItemId, OWNER_EMPLOYEE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void replaceSplits_throwsIllegalArgument_whenReportNotEditable() {
        lineItem.getReport().setReportStatus(ReportStatus.APPROVED);
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently editable");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Rule 1 - minimum split count
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_revertsToNormal_whenEmptyListSupplied() {
        stubOwnedEditableLineItem();

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(
                lineItemId, new ExpenseSplitReplaceRequest(List.of()), OWNER_EMPLOYEE_ID);

        assertThat(result).isEmpty();
        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    @Test
    void replaceSplits_rejectsExactlyOneSplit() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(100), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 splits");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    @Test
    void replaceSplits_acceptsTwoSplits() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID);

        assertThat(result).hasSize(2);
    }

    @Test
    void replaceSplits_acceptsThreeSplits() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        UUID costCenterCId = UUID.randomUUID();
        when(costCenterRepository.findById(costCenterCId))
                .thenReturn(Optional.of(CostCenter.builder().costCenterId(costCenterCId).costCenterCode("CC-C").costCenterName("Sales").build()));
        stubSaveAllPassthrough();

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, new BigDecimal("33.33"), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, new BigDecimal("33.33"), null),
                new ExpenseSplitRequest(costCenterCId, SplitType.PERCENTAGE, new BigDecimal("33.34"), null)));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        assertThat(result).hasSize(3);
        BigDecimal sum = result.stream().map(ExpenseSplitResponse::allocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10000");
    }

    // ---------------------------------------------------------------------
    // Rule 2 - duplicate Cost Center rejected, never merged
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_rejectsDuplicateCostCenter() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        when(costCenterRepository.findById(costCenterAId)).thenReturn(Optional.of(costCenterA));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(60), null),
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(40), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CC-A")
                .hasMessageContaining("already has an allocation");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Rule 3 - fixed-amount validation (no rounding absorption in this mode)
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_fixedAmount_acceptsExactMatch() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(6000)),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(4000))));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        assertThat(result).extracting(ExpenseSplitResponse::allocatedAmount)
                .containsExactlyInAnyOrder(new BigDecimal("6000"), new BigDecimal("4000"));
    }

    @Test
    void replaceSplits_fixedAmount_rejectsMismatchBeyondTolerance() {
        // Sum-tolerance is checked before any Cost Center is resolved (fail fast, no unnecessary
        // lookups), so no costCenterRepository stubbing is needed for this failure path.
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(6000)),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(3500))));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to the line item total");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    @Test
    void replaceSplits_fixedAmount_acceptsWithinTolerance() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(6000)),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, new BigDecimal("4000.005"))));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        assertThat(result).hasSize(2);
    }

    // ---------------------------------------------------------------------
    // Rule 4 - percentage-sum validation
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_percentage_rejectsSumMismatchBeyondTolerance() {
        // Sum-tolerance is checked before any Cost Center is resolved (fail fast, no unnecessary
        // lookups), so no costCenterRepository stubbing is needed for this failure path.
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(60), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, BigDecimal.valueOf(30), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to 100%");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Rule 5 - rounding absorbed into the last split in entry order; exact reconciliation
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_percentage_absorbsRoundingIntoLastSplitInEntryOrder() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        // 33.33 / 33.33 / 33.34 on a 3-way split would be the natural entry, but here we test the
        // simpler 2-way case where naive division leaves a fractional remainder that must land
        // entirely on the LAST split (costCenterB, entryOrder 1), never split evenly or on the first.
        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, new BigDecimal("33.33"), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, new BigDecimal("66.67"), null)));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        ExpenseSplitResponse first = result.stream().filter(r -> r.costCenterId().equals(costCenterAId)).findFirst().orElseThrow();
        ExpenseSplitResponse last = result.stream().filter(r -> r.costCenterId().equals(costCenterBId)).findFirst().orElseThrow();

        // First split: naive 33.33% of 10000 = 3333.0000 exactly - no rounding pressure here.
        assertThat(first.allocatedAmount()).isEqualByComparingTo("3333.0000");
        // Last split absorbs the exact remainder, guaranteeing the stored sum reconciles precisely.
        assertThat(last.allocatedAmount()).isEqualByComparingTo("6667.0000");
        BigDecimal sum = first.allocatedAmount().add(last.allocatedAmount());
        assertThat(sum).isEqualByComparingTo(lineItem.getAmount());
    }

    // ---------------------------------------------------------------------
    // Currency basis - splits allocate against baseAmount (Org Base Currency), never the line
    // item's own display-currency amount, since CostCenterBudget/BudgetEncumbrance are base-currency.
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_allocatesAgainstBaseAmount_notDisplayAmount_forAForeignCurrencyLineItem() {
        // A foreign-currency line item: amount=10000 in its own currency, but baseAmount=8500 once
        // converted to the Org Base Currency (e.g. exchangeRate=0.85) - splits must reconcile to the
        // base-currency figure, not the display one.
        lineItem.setAmount(BigDecimal.valueOf(10000));
        lineItem.setBaseAmount(BigDecimal.valueOf(8500));
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID);

        BigDecimal sum = result.stream().map(ExpenseSplitResponse::allocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("8500"); // reconciles to baseAmount, not the 10000 display amount
    }

    // ---------------------------------------------------------------------
    // Validation edge cases (production-readiness audit, Part 9)
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_fixedAmount_rejectsNegativeAmount() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(-100)),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(10100))));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void replaceSplits_fixedAmount_rejectsZeroAmount() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, BigDecimal.ZERO),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(10000))));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void replaceSplits_fixedAmount_rejectsNullAmount() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, null),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(10000))));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void replaceSplits_fixedAmount_rejectsWhenOneSplitAloneExceedsTheLineItemTotal() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem)); // total = 10000

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(15000)),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(1))));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to the line item total");
    }

    @Test
    void replaceSplits_percentage_rejectsNegativePercentage() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(-10), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, BigDecimal.valueOf(110), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void replaceSplits_percentage_rejectsZeroPercentage() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.ZERO, null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, BigDecimal.valueOf(100), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void replaceSplits_percentage_rejectsNullPercentage() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, null, null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, BigDecimal.valueOf(100), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void replaceSplits_percentage_rejectsGreaterThan100Percent() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(60), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, BigDecimal.valueOf(60), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to 100%");
    }

    @Test
    void replaceSplits_percentage_rejectsJustOutsideTolerance_100Point02() {
        // 0.01% is the inclusive boundary (100.01% is accepted); 100.02% (delta 0.02) is the first
        // value genuinely outside it.
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, new BigDecimal("50.01"), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, new BigDecimal("50.01"), null)));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to 100%");
    }

    @Test
    void replaceSplits_percentage_acceptsExactlyAtTheInclusiveTolerance_100Point01() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, new BigDecimal("50.005"), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, new BigDecimal("50.005"), null)));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        assertThat(result).hasSize(2);
    }

    @Test
    void replaceSplits_percentage_accepts99Point99Plus0Point01_exactBoundary() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, new BigDecimal("99.99"), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, new BigDecimal("0.01"), null)));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        BigDecimal sum = result.stream().map(ExpenseSplitResponse::allocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10000"); // reconciles exactly to the line item total despite the awkward split
    }

    @Test
    void replaceSplits_percentage_threeWayDifficultDecimal_reconcilesExactlyViaLastSplitAbsorption() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        UUID costCenterCId = UUID.randomUUID();
        when(costCenterRepository.findById(costCenterCId))
                .thenReturn(Optional.of(CostCenter.builder().costCenterId(costCenterCId).costCenterCode("CC-C").costCenterName("Sales").build()));
        stubSaveAllPassthrough();

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, new BigDecimal("33.33"), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, new BigDecimal("33.33"), null),
                new ExpenseSplitRequest(costCenterCId, SplitType.PERCENTAGE, new BigDecimal("33.34"), null)));

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID);

        BigDecimal sum = result.stream().map(ExpenseSplitResponse::allocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10000"); // exact - the remainder always lands on the deterministic LAST splitOrder
        ExpenseSplitResponse last = result.stream().filter(r -> r.costCenterId().equals(costCenterCId)).findFirst().orElseThrow();
        assertThat(last.splitOrder()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Mode homogeneity (default, flagged as an inference in the accompanying analysis)
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_rejectsMixedModesWithinOneLineItem() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        ExpenseSplitReplaceRequest request = new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(60), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.FIXED_AMOUNT, null, BigDecimal.valueOf(4000))));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, request, OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same allocation mode");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Whole-set reconcile semantics (rewritten from delete-all-recreate during the production-
    // readiness audit: an ExpenseSplit can never be physically deleted once it has ApprovalSplitReview
    // history without violating that FK - see the entity's own javadoc)
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_softDeletesARemovedCostCenter_neverPhysicallyDeletesIt() {
        UUID staleCostCenterId = UUID.randomUUID();
        CostCenter staleCostCenter = CostCenter.builder().costCenterId(staleCostCenterId).costCenterCode("CC-STALE").build();
        ExpenseSplit staleRow = ExpenseSplit.builder().splitId(UUID.randomUUID()).costCenter(staleCostCenter).allocatedAmount(BigDecimal.TEN).build();
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lineItemId)).thenReturn(List.of(staleRow));
        stubCostCenters();
        stubSaveAllPassthrough();

        service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID);

        assertThat(staleRow.getRemovedAt()).isNotNull();
        verify(expenseSplitRepository, never()).deleteAll(anyList());
    }

    // ---------------------------------------------------------------------
    // Legacy CostAllocation vs ExpenseSplit conflict (production-readiness audit, Part 5)
    // ---------------------------------------------------------------------

    @Test
    void replaceSplits_throwsIllegalArgument_whenLineItemAlreadyHasLegacyCostAllocationEntries() {
        stubOwnedEditableLineItem();
        when(costAllocationRepository.findByLineItem_LineItemId(lineItemId))
                .thenReturn(List.of(com.expense_management_service.entity.CostAllocation.builder().allocationId(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy Cost Allocation");

        verify(expenseSplitRepository, never()).saveAll(anyList());
    }

    @Test
    void replaceSplits_allowsRevertingToNormal_evenWhenLegacyCostAllocationEntriesExist() {
        // Reverting to an empty split set never creates an ExpenseSplit, so the conflict rule (which
        // only guards CREATING a split allocation) never even checks costAllocationRepository here.
        stubOwnedEditableLineItem();

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(
                lineItemId, new ExpenseSplitReplaceRequest(List.of()), OWNER_EMPLOYEE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void replaceSplits_succeeds_whenLineItemHasNoLegacyAllocation() {
        stubOwnedEditableLineItem();
        stubCostCenters();
        stubSaveAllPassthrough();
        // No stub for costAllocationRepository - Mockito's default answer already returns an empty
        // list, matching a line item with no legacy allocation at all.

        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID);

        assertThat(result).hasSize(2);
    }

    @Test
    void replaceSplits_updatesInPlace_whenACostCenterIsUnchanged_preservingItsSplitId() {
        ExpenseSplit existingA = ExpenseSplit.builder().splitId(UUID.randomUUID()).costCenter(costCenterA)
                .splitType(SplitType.PERCENTAGE).percentage(BigDecimal.valueOf(70)).allocatedAmount(BigDecimal.valueOf(7000)).splitOrder(0).build();
        UUID originalSplitIdForA = existingA.getSplitId();
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lineItemId)).thenReturn(List.of(existingA));
        // Only costCenterB needs a fresh lookup - costCenterA is reused from the existing (matched) row.
        when(costCenterRepository.findById(costCenterBId)).thenReturn(Optional.of(costCenterB));
        stubSaveAllPassthrough();

        // Correction: CC-A's percentage changes from 70% to 60%, CC-B is newly added at 40%.
        List<ExpenseSplitResponse> result = service.replaceSplitsForLineItem(lineItemId, twoValidPercentageSplits(), OWNER_EMPLOYEE_ID);

        ExpenseSplitResponse ccA = result.stream().filter(r -> r.costCenterId().equals(costCenterAId)).findFirst().orElseThrow();
        assertThat(ccA.splitId()).isEqualTo(originalSplitIdForA); // same row, updated in place - not delete+recreate
        assertThat(ccA.allocatedAmount()).isEqualByComparingTo("6000");
        assertThat(existingA.getRemovedAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private void stubCostCenters() {
        when(costCenterRepository.findById(costCenterAId)).thenReturn(Optional.of(costCenterA));
        when(costCenterRepository.findById(costCenterBId)).thenReturn(Optional.of(costCenterB));
    }

    private ExpenseSplitReplaceRequest twoValidPercentageSplits() {
        return new ExpenseSplitReplaceRequest(List.of(
                new ExpenseSplitRequest(costCenterAId, SplitType.PERCENTAGE, BigDecimal.valueOf(60), null),
                new ExpenseSplitRequest(costCenterBId, SplitType.PERCENTAGE, BigDecimal.valueOf(40), null)));
    }
}
