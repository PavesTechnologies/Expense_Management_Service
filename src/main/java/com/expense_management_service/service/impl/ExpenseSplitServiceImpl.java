package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseSplitReplaceRequest;
import com.expense_management_service.dto.request.ExpenseSplitRequest;
import com.expense_management_service.dto.response.ExpenseSplitResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.mapper.ExpenseSplitMapper;
import com.expense_management_service.repository.CostAllocationRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseSplitRepository;
import com.expense_management_service.service.ExpenseSplitService;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Group 1's LOCKED validation rules, enforced as one atomic, whole-set replace operation (see
 * {@link ExpenseSplitService}'s own javadoc for why this is not per-row CRUD like
 * {@code CostAllocationService}).
 * <p>
 * Deliberately conservative on authorization compared to {@code CostAllocationServiceImpl} (which
 * has no ownership check at all - a gap identified, not repeated here): every operation requires the
 * acting employee to own the line item's report, and the report must currently be editable
 * ({@code ReportStatus.isEditable()}). No admin/finance/manager override is implemented - if that is
 * ever wanted, it needs its own explicit decision, not an inferred one.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseSplitServiceImpl implements ExpenseSplitService {

    /** Rule 4: percentages must sum to 100%, within this tolerance. */
    private static final BigDecimal PERCENTAGE_TOTAL = BigDecimal.valueOf(100);
    private static final BigDecimal PERCENTAGE_TOLERANCE = new BigDecimal("0.01");
    /** Rule 3: amounts must sum to the line item total, within this tolerance. */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    private final ExpenseSplitRepository expenseSplitRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final CostCenterRepository costCenterRepository;
    private final CostAllocationRepository costAllocationRepository;
    private final ExpenseSplitMapper expenseSplitMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseSplitResponse> getSplitsForLineItem(UUID lineItemId, String actingEmployeeId) {
        ExpenseLineItem lineItem = findOwnedLineItem(lineItemId, actingEmployeeId);
        return expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lineItem.getLineItemId()).stream()
                .map(expenseSplitMapper::toResponse)
                .toList();
    }

    /**
     * Reconciles in place rather than delete-all-and-recreate (production-readiness audit finding,
     * post-Phase-8): once a split has any {@code ApprovalSplitReview} history, its row can never be
     * physically deleted (the FK would be violated), and that history must survive for audit anyway.
     * A split whose Cost Center is unchanged is UPDATED in place (same {@code splitId} - its review
     * history, if any, stays attached and is re-evaluated for material change by {@code
     * ApprovalWorkflowServiceImpl}'s resume-time reconciliation, which compares {@code
     * ExpenseSplit.updatedAt} against the active level instance's materialization time - Hibernate's
     * own dirty-checking already leaves {@code updatedAt} untouched when nothing about a split
     * actually changed, so no separate "did this change" comparison is needed here). A Cost Center
     * dropped from the set is soft-deleted ({@code removedAt}); a newly-added one is a fresh row.
     */
    @Override
    public List<ExpenseSplitResponse> replaceSplitsForLineItem(UUID lineItemId, ExpenseSplitReplaceRequest request, String actingEmployeeId) {
        ExpenseLineItem lineItem = findOwnedLineItem(lineItemId, actingEmployeeId);
        assertEditable(lineItem);

        List<ExpenseSplitRequest> incoming = request.splits() == null ? List.of() : request.splits();

        // Rule 1: 0 (revert to NORMAL) or 2+; exactly 1 is never valid.
        if (incoming.size() == 1) {
            throw new IllegalArgumentException(
                    "A split allocation must contain at least 2 splits - a single split is not a valid allocation. "
                            + "Remove the split entirely to keep this line item as a normal, unsplit expense.");
        }

        List<ExpenseSplit> existing = expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lineItemId);

        if (incoming.isEmpty()) {
            // Revert to NORMAL: soft-delete every currently-active split - never a physical delete,
            // since any of them may already carry ApprovalSplitReview history.
            softDeleteAll(existing);
            return List.of();
        }

        // Production-readiness audit (Part 5): a line item must use CostAllocation (legacy) or
        // ExpenseSplit (new), never both - establishing an ExpenseSplit set is rejected outright if
        // legacy allocations already exist, rather than silently letting both models coexist. No
        // automatic migration is performed; an admin must explicitly clear the legacy rows first
        // (via the existing CostAllocation endpoints) if the new split model is what's actually wanted.
        if (!costAllocationRepository.findByLineItem_LineItemId(lineItemId).isEmpty()) {
            throw new IllegalArgumentException(
                    "This line item already has legacy Cost Allocation entries - remove them before creating an ExpenseSplit "
                            + "allocation. A line item may use Cost Allocation or Expense Split, never both.");
        }

        assertNoDuplicateCostCenters(incoming);
        SplitType mode = assertSingleModeAndReturn(incoming);

        // Splits are allocated in the Organization Base Currency (baseAmount), not the line item's
        // own display currency (amount) - CostCenterBudget/BudgetEncumbrance are both base-currency
        // pools, and a split's allocatedAmount must reconcile against that same basis. For a line
        // item whose currency IS the base currency, baseAmount == amount, so this is a no-op change.
        BigDecimal lineItemTotal = lineItem.getBaseAmount();
        Map<UUID, ExpenseSplit> existingByCostCenter = existing.stream()
                .collect(Collectors.toMap(s -> s.getCostCenter().getCostCenterId(), Function.identity(), (a, b) -> a, HashMap::new));

        List<ExpenseSplit> reconciled = mode == SplitType.PERCENTAGE
                ? buildPercentageSplits(lineItem, incoming, lineItemTotal, existingByCostCenter)
                : buildFixedAmountSplits(lineItem, incoming, lineItemTotal, existingByCostCenter);

        // Anything left unmatched had its Cost Center dropped from the allocation - soft-delete it.
        softDeleteAll(existingByCostCenter.values());

        return expenseSplitRepository.saveAll(reconciled).stream()
                .map(expenseSplitMapper::toResponse)
                .toList();
    }

    private void softDeleteAll(java.util.Collection<ExpenseSplit> splits) {
        if (splits.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        splits.forEach(s -> s.setRemovedAt(now));
        expenseSplitRepository.saveAll(new ArrayList<>(splits));
    }

    // -------------------------------------------------------------------
    // Rule 2 - duplicate Cost Center rejected, never auto-merged
    // -------------------------------------------------------------------
    private void assertNoDuplicateCostCenters(List<ExpenseSplitRequest> incoming) {
        Set<UUID> seen = new HashSet<>();
        for (ExpenseSplitRequest r : incoming) {
            if (!seen.add(r.costCenterId())) {
                CostCenter duplicate = findCostCenter(r.costCenterId());
                throw new IllegalArgumentException(
                        "Cost Center " + duplicate.getCostCenterCode() + " already has an allocation on this line item - "
                                + "combine them into a single split instead of two separate ones for the same cost center.");
            }
        }
    }

    /** All splits on one line item must share one allocation mode - mixing PERCENTAGE and FIXED_AMOUNT within one line item is not supported. */
    private SplitType assertSingleModeAndReturn(List<ExpenseSplitRequest> incoming) {
        SplitType mode = incoming.get(0).splitType();
        for (ExpenseSplitRequest r : incoming) {
            if (r.splitType() != mode) {
                throw new IllegalArgumentException(
                        "All splits on one line item must use the same allocation mode - mixing PERCENTAGE and FIXED_AMOUNT within one line item is not supported.");
            }
        }
        return mode;
    }

    // -------------------------------------------------------------------
    // PERCENTAGE mode - Rule 4 (100% tolerance) + Rule 5 (rounding absorbed into the last split)
    // -------------------------------------------------------------------
    private List<ExpenseSplit> buildPercentageSplits(ExpenseLineItem lineItem, List<ExpenseSplitRequest> incoming, BigDecimal lineItemTotal,
                                                       Map<UUID, ExpenseSplit> existingByCostCenter) {
        BigDecimal percentageSum = BigDecimal.ZERO;
        for (ExpenseSplitRequest r : incoming) {
            if (r.percentage() == null || r.percentage().signum() <= 0) {
                throw new IllegalArgumentException("Each PERCENTAGE split must have a percentage greater than zero.");
            }
            percentageSum = percentageSum.add(r.percentage());
        }
        if (percentageSum.subtract(PERCENTAGE_TOTAL).abs().compareTo(PERCENTAGE_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    "Split percentages must sum to 100% (tolerance +/-0.01%) - got " + percentageSum.stripTrailingZeros().toPlainString() + "%.");
        }

        List<ExpenseSplit> result = new ArrayList<>(incoming.size());
        BigDecimal runningTotal = BigDecimal.ZERO;
        for (int i = 0; i < incoming.size(); i++) {
            ExpenseSplitRequest r = incoming.get(i);
            boolean isLast = i == incoming.size() - 1;

            BigDecimal amount;
            if (isLast) {
                // Rule 5: the entire rounding remainder is absorbed here, in deterministic entry
                // order, so the stored sum reconciles EXACTLY to the line item total regardless of
                // how the entered percentages themselves rounded.
                amount = lineItemTotal.subtract(runningTotal).setScale(4, RoundingMode.HALF_UP);
                if (amount.signum() < 0) {
                    throw new IllegalArgumentException(
                            "The computed allocation for the last split is negative - check the entered percentages.");
                }
            } else {
                amount = lineItemTotal.multiply(r.percentage()).divide(PERCENTAGE_TOTAL, 4, RoundingMode.HALF_UP);
                runningTotal = runningTotal.add(amount);
            }

            result.add(reconcileOne(lineItem, r, SplitType.PERCENTAGE, r.percentage(), amount, i, existingByCostCenter));
        }
        return result;
    }

    /** Cost Center unchanged -> update the existing row in place (preserves splitId and any approval history); otherwise a fresh row. */
    private ExpenseSplit reconcileOne(ExpenseLineItem lineItem, ExpenseSplitRequest r, SplitType splitType, BigDecimal percentage,
                                       BigDecimal amount, int splitOrder, Map<UUID, ExpenseSplit> existingByCostCenter) {
        ExpenseSplit split = existingByCostCenter.remove(r.costCenterId());
        if (split != null) {
            split.setSplitType(splitType);
            split.setPercentage(percentage);
            split.setAllocatedAmount(amount);
            split.setSplitOrder(splitOrder);
            return split;
        }
        CostCenter costCenter = findCostCenter(r.costCenterId());
        return ExpenseSplit.builder()
                .lineItem(lineItem)
                .costCenter(costCenter)
                .splitType(splitType)
                .percentage(percentage)
                .allocatedAmount(amount)
                .splitOrder(splitOrder)
                .build();
    }

    // -------------------------------------------------------------------
    // FIXED_AMOUNT mode - Rule 3 (amount tolerance) only; no rounding-remainder absorption (Rule 5:
    // "For fixed amount mode: No percentage rounding logic. Only amount validation applies.")
    // -------------------------------------------------------------------
    private List<ExpenseSplit> buildFixedAmountSplits(ExpenseLineItem lineItem, List<ExpenseSplitRequest> incoming, BigDecimal lineItemTotal,
                                                        Map<UUID, ExpenseSplit> existingByCostCenter) {
        BigDecimal amountSum = BigDecimal.ZERO;
        for (ExpenseSplitRequest r : incoming) {
            if (r.allocatedAmount() == null || r.allocatedAmount().signum() <= 0) {
                throw new IllegalArgumentException("Each FIXED_AMOUNT split must have an allocated amount greater than zero.");
            }
            amountSum = amountSum.add(r.allocatedAmount());
        }
        if (amountSum.subtract(lineItemTotal).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    "Split amounts must sum to the line item total of " + lineItemTotal.stripTrailingZeros().toPlainString()
                            + " (tolerance +/-0.01) - got " + amountSum.stripTrailingZeros().toPlainString() + ".");
        }

        List<ExpenseSplit> result = new ArrayList<>(incoming.size());
        for (int i = 0; i < incoming.size(); i++) {
            ExpenseSplitRequest r = incoming.get(i);
            result.add(reconcileOne(lineItem, r, SplitType.FIXED_AMOUNT, null, r.allocatedAmount(), i, existingByCostCenter));
        }
        return result;
    }

    private ExpenseLineItem findOwnedLineItem(UUID lineItemId, String actingEmployeeId) {
        ExpenseLineItem lineItem = expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
        if (!Objects.equals(lineItem.getReport().getEmployeeId(), actingEmployeeId)) {
            throw new AccessDeniedException("You may only manage splits on your own expense reports.");
        }
        return lineItem;
    }

    private void assertEditable(ExpenseLineItem lineItem) {
        if (!lineItem.getReport().getReportStatus().isEditable()) {
            throw new IllegalArgumentException(
                    "Expense report is not currently editable (status: " + lineItem.getReport().getReportStatus() + ").");
        }
    }

    private CostCenter findCostCenter(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }
}
