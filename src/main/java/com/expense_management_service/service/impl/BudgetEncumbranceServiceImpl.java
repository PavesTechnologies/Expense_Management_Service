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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
                throw new IllegalArgumentException(insufficientBudgetMessage(costCenter.getCostCenterCode(), "this submission needs",
                        needed, budget.getAvailableBudget(), effectiveAvailable));
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

    /**
     * Spells out why: the budget page's "Available" is budget less payments made, while a submission
     * is checked against that less what other submitted-but-unpaid reports have reserved.
     */
    static String insufficientBudgetMessage(String costCenterCode, String needsPhrase, BigDecimal needed,
                                            BigDecimal available, BigDecimal effectiveAvailable) {
        BigDecimal reserved = available != null ? available.subtract(effectiveAvailable) : null;
        String why = reserved != null && reserved.signum() > 0
                ? " (" + money(available) + " left after payments, minus " + money(reserved)
                        + " reserved by other reports awaiting approval or payment)"
                : "";
        return "Insufficient budget for Cost Center " + costCenterCode + ": " + needsPhrase + " " + money(needed)
                + ", but only " + money(effectiveAvailable) + " is available" + why + ".";
    }

    private static String money(BigDecimal value) {
        return value == null ? "0.00" : String.format(java.util.Locale.ROOT, "%,.2f", value);
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

    /**
     * Reconciles this report/cycle's encumbrances against the current line items - IN PLACE, never
     * by releasing and re-inserting.
     * <p>
     * Bug fix (production-readiness follow-up): the previous implementation released every currently-
     * ACTIVE encumbrance (an UPDATE to RELEASED - the row still physically exists) and then called
     * {@link #validateAndEncumber} to INSERT a fresh set. For any split/line item whose identity
     * (splitId, or the single null-split "unsplit remainder") survived the correction unchanged, this
     * tried to insert a second row with the exact same {@code (report_id, submission_cycle, split_id)}
     * key the just-released row still occupies - {@code uk_budget_encumbrance_report_cycle_split} does
     * not distinguish by status, so this always failed with a duplicate-key error the moment a
     * Reporting Manager requested correction on a split line item and the employee resubmitted with
     * that split's cost center unchanged (the overwhelmingly common case - {@code
     * ExpenseSplitServiceImpl#reconcileOne} only ever assigns a NEW splitId when the split's Cost
     * Center itself changes, never for a plain amount/percentage edit). Existing Mockito-based tests
     * never caught this because a mocked repository has no unique constraint to violate.
     * <p>
     * Fix: diff current targets against currently-ACTIVE encumbrances by identity (splitId, or the
     * null-split remainder) - a survived identity is updated in place (amount only; {@code budget}/
     * {@code unbudgeted} stay immutable per this entity's own documented invariant), a genuinely new
     * identity is inserted (safe - it has never had a row for this report/cycle), and an identity no
     * longer present is released. Budget validation is delta-based: {@code effectiveAvailable} already
     * excludes this report's own not-yet-touched old amount (it is still ACTIVE at the moment this
     * checks), so only {@code newAmount - oldAmount} (the report's OWN incremental need for that Cost
     * Center) is compared against it - a correction that reduces or leaves a Cost Center's total
     * unchanged can never fail validation, matching {@code validateAndEncumber}'s existing "atomic,
     * no partial state" guarantee (every row touch happens only after every Cost Center passes).
     */
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

        log.info("Report {} cycle {}: budget-impacting change detected during correction - reconciling encumbrances in place",
                report.getReportId(), submissionCycle);

        Map<UUID, BudgetEncumbrance> activeBySplitId = new HashMap<>();
        BudgetEncumbrance activeRemainder = null;
        for (BudgetEncumbrance e : active) {
            if (e.getSplit() != null) {
                activeBySplitId.put(e.getSplit().getSplitId(), e);
            } else {
                activeRemainder = e; // invariant: at most one null-split row per report/cycle (see class javadoc)
            }
        }

        record Reconciliation(BudgetEncumbrance existing, Target target) {
        }
        List<Reconciliation> toUpdate = new ArrayList<>();
        List<Target> toCreate = new ArrayList<>();
        Set<UUID> matchedSplitIds = new HashSet<>();
        boolean remainderMatched = false;

        for (Target target : currentTargets) {
            if (target.split() != null) {
                BudgetEncumbrance existing = activeBySplitId.get(target.split().getSplitId());
                if (existing == null) {
                    toCreate.add(target);
                } else {
                    matchedSplitIds.add(target.split().getSplitId());
                    if (existing.getAmount().compareTo(target.amount()) != 0) {
                        toUpdate.add(new Reconciliation(existing, target));
                    }
                }
            } else if (activeRemainder == null) {
                toCreate.add(target);
            } else {
                remainderMatched = true;
                if (activeRemainder.getAmount().compareTo(target.amount()) != 0) {
                    toUpdate.add(new Reconciliation(activeRemainder, target));
                }
            }
        }

        List<BudgetEncumbrance> toRelease = new ArrayList<>();
        for (BudgetEncumbrance e : active) {
            if (e.getSplit() != null) {
                if (!matchedSplitIds.contains(e.getSplit().getSplitId())) {
                    toRelease.add(e);
                }
            } else if (!remainderMatched) {
                toRelease.add(e);
            }
        }

        // This report's own OWN prior amount per Cost Center (budgeted rows only - an unbudgeted row
        // never participated in a budget check and never will, per this entity's immutable-budget
        // invariant), so it can be netted out of the corrected total before validating.
        Map<UUID, BigDecimal> oldAmountByCostCenter = new HashMap<>();
        for (BudgetEncumbrance e : active) {
            if (Boolean.TRUE.equals(e.getUnbudgeted()) || e.getBudget() == null) {
                continue;
            }
            oldAmountByCostCenter.merge(e.getBudget().getCostCenter().getCostCenterId(), e.getAmount(), BigDecimal::add);
        }
        Map<UUID, BigDecimal> newAmountByCostCenter = new LinkedHashMap<>();
        Map<UUID, CostCenter> costCenterById = new LinkedHashMap<>();
        for (Target target : currentTargets) {
            newAmountByCostCenter.merge(target.costCenter().getCostCenterId(), target.amount(), BigDecimal::add);
            costCenterById.putIfAbsent(target.costCenter().getCostCenterId(), target.costCenter());
        }

        // Sorted, same deadlock-avoidance rationale as validateAndEncumber (Decision 7).
        Set<UUID> costCenterIdsToCheck = new TreeSet<>();
        costCenterIdsToCheck.addAll(newAmountByCostCenter.keySet());
        costCenterIdsToCheck.addAll(oldAmountByCostCenter.keySet());

        Map<UUID, CostCenterBudget> lockedBudgets = new LinkedHashMap<>();
        List<BudgetWarning> warnings = new ArrayList<>();
        for (UUID costCenterId : costCenterIdsToCheck) {
            BigDecimal newAmount = newAmountByCostCenter.getOrDefault(costCenterId, BigDecimal.ZERO);
            BigDecimal oldAmount = oldAmountByCostCenter.getOrDefault(costCenterId, BigDecimal.ZERO);
            BigDecimal incremental = newAmount.subtract(oldAmount);
            if (incremental.signum() <= 0) {
                continue; // this Cost Center now needs the same amount or less - never a validation failure
            }

            CostCenter costCenter = costCenterById.get(costCenterId);
            Optional<CostCenterBudget> budgetOpt = costCenterBudgetRepository
                    .findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, report.getFiscalYear());
            if (budgetOpt.isEmpty()) {
                if (!Boolean.TRUE.equals(costCenter.getAllowUnbudgeted())) {
                    throw new IllegalArgumentException("No budget is configured for Cost Center " + costCenter.getCostCenterCode()
                            + " (fiscal year " + report.getFiscalYear() + "), and unbudgeted spending is not allowed for this cost center.");
                }
                continue;
            }

            CostCenterBudget budget = budgetOpt.get();
            BigDecimal effectiveAvailable = effectiveAvailable(budget);
            if (incremental.compareTo(effectiveAvailable) > 0) {
                throw new IllegalArgumentException(insufficientBudgetMessage(costCenter.getCostCenterCode(), "this correction needs an additional",
                        incremental, budget.getAvailableBudget(), effectiveAvailable));
            }

            BigDecimal effectiveAvailableAfter = effectiveAvailable.subtract(incremental);
            if (budget.getWarningThreshold() != null
                    && effectiveAvailableAfter.signum() >= 0
                    && effectiveAvailableAfter.compareTo(budget.getWarningThreshold()) < 0) {
                warnings.add(new BudgetWarning(costCenterId, costCenter.getCostCenterCode(), effectiveAvailableAfter, budget.getWarningThreshold()));
            }
            lockedBudgets.put(costCenterId, budget);
        }
        for (BudgetWarning warning : warnings) {
            log.warn("Cost center {} Effective Available Budget fell below its warning threshold ({}) after this correction's encumbrance change: now {}",
                    warning.costCenterCode(), warning.warningThreshold(), warning.effectiveAvailableAfterEncumbrance());
        }

        // Every Cost Center passed (or was legitimately unbudgeted) before any row is touched -
        // matches validateAndEncumber's existing atomicity guarantee.
        if (!toUpdate.isEmpty()) {
            for (Reconciliation r : toUpdate) {
                r.existing().setAmount(r.target().amount());
            }
            budgetEncumbranceRepository.saveAll(toUpdate.stream().map(Reconciliation::existing).toList());
        }

        if (!toRelease.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            toRelease.forEach(e -> {
                e.setStatus(BudgetEncumbranceStatus.RELEASED);
                e.setReleasedAt(now);
            });
            budgetEncumbranceRepository.saveAll(toRelease);
        }

        if (!toCreate.isEmpty()) {
            List<BudgetEncumbrance> newRows = new ArrayList<>();
            for (Target target : toCreate) {
                CostCenterBudget budget = lockedBudgets.computeIfAbsent(target.costCenter().getCostCenterId(),
                        id -> costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(id, report.getFiscalYear())
                                .orElse(null));
                newRows.add(BudgetEncumbrance.builder()
                        .budget(budget)
                        .report(report)
                        .split(target.split())
                        .submissionCycle(submissionCycle)
                        .amount(target.amount())
                        .status(BudgetEncumbranceStatus.ACTIVE)
                        .unbudgeted(budget == null)
                        .build());
            }
            budgetEncumbranceRepository.saveAll(newRows);
        }

        log.info("Report {} cycle {}: reconciled encumbrances - {} updated in place, {} released, {} newly created",
                report.getReportId(), submissionCycle, toUpdate.size(), toRelease.size(), toCreate.size());
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
