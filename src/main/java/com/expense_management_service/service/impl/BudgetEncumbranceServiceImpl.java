package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.BudgetEncumbranceOutcome;
import com.expense_management_service.dto.response.BudgetWarning;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.mapper.BudgetEncumbranceMapper;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.CostCenterBudgetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetEncumbranceServiceImpl implements BudgetEncumbranceService {

    private final BudgetEncumbranceRepository budgetEncumbranceRepository;
    private final CostCenterBudgetRepository costCenterBudgetRepository;
    private final CostCenterBudgetService costCenterBudgetService;
    private final BudgetEncumbranceMapper budgetEncumbranceMapper;

    /** One resolved reservation target - either a specific ExpenseSplit, or the combined unsplit remainder (split == null) against the report's own header Cost Center. */
    private record Target(CostCenter costCenter, BigDecimal amount, ExpenseSplit split) {
    }

    @Override
    public BudgetEncumbranceOutcome validateAndEncumber(ExpenseReport report, int submissionCycle) {
        List<Target> targets = resolveTargets(report);
        if (targets.isEmpty()) {
            return new BudgetEncumbranceOutcome(List.of(), List.of());
        }

        // Q10 (mega-review): the same Cost Center appearing across multiple splits/line items is
        // validated as ONE combined need, never checked twice against the same shrinking figure.
        Map<UUID, BigDecimal> neededByCostCenter = new LinkedHashMap<>();
        Map<UUID, CostCenter> costCenterById = new LinkedHashMap<>();
        for (Target target : targets) {
            neededByCostCenter.merge(target.costCenter().getCostCenterId(), target.amount(), BigDecimal::add);
            costCenterById.putIfAbsent(target.costCenter().getCostCenterId(), target.costCenter());
        }

        // Decision 7: lock every distinct Cost Center's budget row in a stable, sorted order, so two
        // concurrent submissions touching overlapping Cost Centers can never deadlock against each other.
        List<UUID> sortedCostCenterIds = neededByCostCenter.keySet().stream().sorted().toList();
        Map<UUID, CostCenterBudget> lockedBudgets = new LinkedHashMap<>();
        List<BudgetWarning> warnings = new ArrayList<>();

        for (UUID costCenterId : sortedCostCenterIds) {
            CostCenter costCenter = costCenterById.get(costCenterId);
            BigDecimal needed = neededByCostCenter.get(costCenterId);

            Optional<CostCenterBudget> budgetOpt = costCenterBudgetRepository
                    .findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, report.getFiscalYear());

            if (budgetOpt.isEmpty()) {
                if (!Boolean.TRUE.equals(costCenter.getAllowUnbudgeted())) {
                    throw new IllegalArgumentException("No budget is configured for Cost Center " + costCenter.getCostCenterCode()
                            + " (fiscal year " + report.getFiscalYear() + "), and unbudgeted spending is not allowed for this cost center.");
                }
                // Rule 17: no budget row exists at all + allowUnbudgeted = true -> proceed with no
                // validation against this Cost Center. Nothing to lock, nothing to record here.
                continue;
            }

            CostCenterBudget budget = budgetOpt.get();
            BigDecimal effectiveAvailable = effectiveAvailable(budget);
            if (needed.compareTo(effectiveAvailable) > 0) {
                throw new IllegalArgumentException("Insufficient budget for Cost Center " + costCenter.getCostCenterCode()
                        + ": this submission requires " + needed + " but only " + effectiveAvailable + " is effectively available.");
            }

            BigDecimal effectiveAvailableAfter = effectiveAvailable.subtract(needed);
            if (budget.getWarningThreshold() != null
                    && effectiveAvailableAfter.signum() >= 0
                    && effectiveAvailableAfter.compareTo(budget.getWarningThreshold()) < 0) {
                warnings.add(new BudgetWarning(costCenterId, costCenter.getCostCenterCode(), effectiveAvailableAfter, budget.getWarningThreshold()));
            }

            lockedBudgets.put(costCenterId, budget);
        }

        // Every distinct Cost Center passed validation (or was legitimately unbudgeted) - create one
        // row per ORIGINAL target (split-level granularity, per Q24), never per aggregated Cost Center.
        List<BudgetEncumbrance> toCreate = new ArrayList<>(targets.size());
        for (Target target : targets) {
            CostCenterBudget budget = lockedBudgets.get(target.costCenter().getCostCenterId());
            toCreate.add(BudgetEncumbrance.builder()
                    .budget(budget)
                    .report(report)
                    .split(target.split())
                    .submissionCycle(submissionCycle)
                    .amount(target.amount())
                    .status(BudgetEncumbranceStatus.ACTIVE)
                    .unbudgeted(budget == null)
                    .build());
        }

        List<BudgetEncumbrance> saved = budgetEncumbranceRepository.saveAll(toCreate);
        log.info("Created {} ACTIVE budget encumbrance(s) for report {} cycle {}", saved.size(), report.getReportId(), submissionCycle);
        return new BudgetEncumbranceOutcome(saved.stream().map(budgetEncumbranceMapper::toResponse).toList(), warnings);
    }

    /** Splits every line item into its own targets when it has any; otherwise its full amount joins the report-level "unsplit remainder" target against report.getCostCenter(). */
    private List<Target> resolveTargets(ExpenseReport report) {
        List<Target> targets = new ArrayList<>();
        BigDecimal unsplitRemainder = BigDecimal.ZERO;

        List<ExpenseLineItem> lineItems = report.getExpenseLineItems() != null ? report.getExpenseLineItems() : List.of();
        for (ExpenseLineItem lineItem : lineItems) {
            // A soft-deleted split (removedAt != null, production-readiness audit finding) is kept
            // only so its historical ApprovalSplitReview can still resolve its FK - it must never be
            // encumbered again, and its amount rejoins the unsplit remainder if no active splits are left.
            List<ExpenseSplit> splits = lineItem.getExpenseSplits() == null ? List.of()
                    : lineItem.getExpenseSplits().stream().filter(s -> s.getRemovedAt() == null).toList();
            if (!splits.isEmpty()) {
                for (ExpenseSplit split : splits) {
                    targets.add(new Target(split.getCostCenter(), split.getAllocatedAmount(), split));
                }
            } else {
                // Base currency, matching ExpenseSplitServiceImpl's allocatedAmount basis and
                // CostCenterBudget's own currency - report.getTotalAmount() is computed the same way
                // (SUM of baseAmount), so this reconciles exactly for an all-normal report.
                unsplitRemainder = unsplitRemainder.add(lineItem.getBaseAmount());
            }
        }

        if (unsplitRemainder.signum() > 0) {
            targets.add(new Target(report.getCostCenter(), unsplitRemainder, null));
        }
        return targets;
    }

    @Override
    public void releaseActiveForCycle(UUID reportId, int submissionCycle) {
        List<BudgetEncumbrance> active = budgetEncumbranceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, submissionCycle, BudgetEncumbranceStatus.ACTIVE);
        if (active.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        active.forEach(encumbrance -> {
            encumbrance.setStatus(BudgetEncumbranceStatus.RELEASED);
            encumbrance.setReleasedAt(now);
        });
        budgetEncumbranceRepository.saveAll(active);
        log.info("Released {} budget encumbrance(s) for report {} cycle {}", active.size(), reportId, submissionCycle);
    }

    @Override
    public boolean consumeActiveForReport(UUID reportId, int submissionCycle) {
        List<BudgetEncumbrance> active = budgetEncumbranceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, submissionCycle, BudgetEncumbranceStatus.ACTIVE);
        if (active.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        for (BudgetEncumbrance encumbrance : active) {
            if (!Boolean.TRUE.equals(encumbrance.getUnbudgeted()) && encumbrance.getBudget() != null) {
                CostCenterBudget budget = encumbrance.getBudget();
                costCenterBudgetService.consumeBudget(budget.getCostCenter(), budget.getFiscalYear(), encumbrance.getAmount());
            }
            encumbrance.setStatus(BudgetEncumbranceStatus.CONSUMED);
            encumbrance.setConsumedAt(now);
        }
        budgetEncumbranceRepository.saveAll(active);
        log.info("Consumed {} budget encumbrance(s) for report {} cycle {}", active.size(), reportId, submissionCycle);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal effectiveAvailable(CostCenterBudget budget) {
        if (budget == null) {
            throw new ResourceNotFoundException("Cannot compute Effective Available Budget for a null CostCenterBudget");
        }
        // Bug 7 (production-readiness audit, real-DB concurrency testing): must be the locking
        // (PESSIMISTIC_READ) read, not the plain sumAmountByBudget_BudgetIdAndStatus aggregate - see
        // that method's javadoc and findForUpdateByBudget_BudgetIdAndStatus's javadoc for why a real
        // MySQL run under REPEATABLE READ needs this to see a concurrently-committed encumbrance.
        BigDecimal activeEncumbered = budgetEncumbranceRepository
                .findForUpdateByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE)
                .stream().map(BudgetEncumbrance::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return budget.getAvailableBudget().subtract(activeEncumbered);
    }

    @Override
    public void reconcileForCycle(ExpenseReport report, int submissionCycle) {
        List<BudgetEncumbrance> active = budgetEncumbranceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(report.getReportId(), submissionCycle, BudgetEncumbranceStatus.ACTIVE);
        List<Target> currentTargets = resolveTargets(report);

        if (matchesExactly(active, currentTargets)) {
            log.info("Report {} cycle {}: encumbrance reconciliation found no budget-impacting change - leaving {} ACTIVE encumbrance(s) untouched",
                    report.getReportId(), submissionCycle, active.size());
            return;
        }

        log.info("Report {} cycle {}: budget-impacting change detected during correction - releasing and re-validating encumbrances",
                report.getReportId(), submissionCycle);
        releaseActiveForCycle(report.getReportId(), submissionCycle);
        validateAndEncumber(report, submissionCycle);
    }

    /**
     * True iff every currently-ACTIVE encumbrance corresponds 1:1 (matched by split identity, or by
     * the single null-split "unsplit remainder" row) to a current target of the identical amount and
     * unbudgeted-ness - i.e. nothing budget-impacting actually changed since these were created. A
     * split's Cost Center never changes in place (see {@code ExpenseSplitServiceImpl}'s reconcile-by-
     * Cost-Center design - a Cost Center change is always modeled as remove-old-split + add-new-
     * split, which is caught here as a splitId that no longer matches any current target), so
     * matching by splitId alone is sufficient to also catch a Cost Center change.
     */
    private boolean matchesExactly(List<BudgetEncumbrance> active, List<Target> currentTargets) {
        if (active.size() != currentTargets.size()) {
            return false;
        }
        Map<UUID, BudgetEncumbrance> activeBySplit = new HashMap<>();
        BudgetEncumbrance activeRemainder = null;
        for (BudgetEncumbrance e : active) {
            if (e.getSplit() != null) {
                activeBySplit.put(e.getSplit().getSplitId(), e);
            } else if (activeRemainder == null) {
                activeRemainder = e;
            } else {
                return false; // more than one null-split row is already an inconsistent state - force reconciliation
            }
        }
        for (Target target : currentTargets) {
            if (target.split() != null) {
                BudgetEncumbrance matched = activeBySplit.remove(target.split().getSplitId());
                if (matched == null || matched.getAmount().compareTo(target.amount()) != 0) {
                    return false;
                }
            } else {
                if (activeRemainder == null || activeRemainder.getAmount().compareTo(target.amount()) != 0) {
                    return false;
                }
                activeRemainder = null;
            }
        }
        return activeBySplit.isEmpty() && activeRemainder == null;
    }
}
