package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Moves money between a cash advance's allocation and the cost center budget. Every budget row is locked
 * with {@code PESSIMISTIC_WRITE} before it is changed, so concurrent offsets, repayments and payments on the
 * same cost center run one at a time.
 *
 * <p>Budget model (per cost center and fiscal year):
 * <ul>
 *   <li>{@code availableBudget} = pool − everything charged. Approval charges the principal, and that
 *       charge stays until the employee repays the unused part.</li>
 *   <li>{@code cashAdvanceAllocated} = the advance principal still held against the budget. It drops when an
 *       offset turns it into a real expense, and when a repayment returns it.</li>
 *   <li>Actual utilization = pool − available − allocated − fees. Nothing is stored; it's derived.</li>
 * </ul>
 *
 * <p>So an offset moves money from {@code allocated} to actual utilization (available stays the same), and a
 * repayment moves money from {@code allocated} back to available. Neither changes the total charged twice.
 * When the offset report splits its cost across other cost centers, their shares are released from the
 * advance's budget and charged to theirs, so the expense still lands once, where it was allocated.
 *
 * <p>An advance that was never charged to a budget ({@code budgetFiscalYear == null}) has nothing to move, and
 * these methods do nothing for it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceBudgetMovements {

    private final CostCenterBudgetRepository costCenterBudgetRepository;

    /** An offset turns part of the allocation into a real expense. Available is unchanged. */
    public void moveAllocationToUtilized(CashAdvance advance, BigDecimal amount) {
        moveAllocationToUtilized(advance, amount, null);
    }

    /**
     * An offset turns part of the allocation into a real expense, booked where the report allocates it.
     * The share on the advance's own cost center moves from allocation to utilization (available unchanged).
     * A share on another cost center (a split, or a line charged elsewhere) is released from the advance's
     * budget and charged to that cost center instead, so each cost center carries only its own part.
     */
    public void moveAllocationToUtilized(CashAdvance advance, BigDecimal amount, ExpenseReport report) {
        if (!isChargedToBudget(advance) || amount == null || amount.signum() <= 0) {
            return;
        }
        List<ReportCostCenterShares.Share> elsewhere = sharesElsewhere(advance, amount, report);
        Map<UUID, CostCenterBudget> locked = lockBudgets(advance, elsewhere, report);
        CostCenterBudget budget = locked.get(advance.getCostCenter().getCostCenterId());
        BigDecimal allocated = nz(budget.getCashAdvanceAllocated());
        if (allocated.compareTo(amount) < 0) {
            throw new BusinessRuleViolationException(
                    "Offset " + amount + " exceeds the cash advance allocation of " + allocated
                            + " held in the " + budget.getFiscalYear() + " budget. Nothing was changed.");
        }
        BigDecimal movedElsewhere = total(elsewhere);
        budget.setCashAdvanceAllocated(allocated.subtract(amount));
        budget.setAvailableBudget(nz(budget.getAvailableBudget()).add(movedElsewhere));
        costCenterBudgetRepository.save(budget);
        for (ReportCostCenterShares.Share share : elsewhere) {
            adjustAvailable(locked.get(share.costCenter().getCostCenterId()), share, share.amount().negate());
        }
        log.info("Offset {} moved from allocation to utilization for advance {} ({}), {} of it charged to other cost centers",
                amount, advance.getAdvanceId(), budget.getFiscalYear(), movedElsewhere);
    }

    /** A confirmed repayment returns unused principal to the budget. */
    public void releaseAllocation(CashAdvance advance, BigDecimal amount) {
        if (!isChargedToBudget(advance) || amount == null || amount.signum() <= 0) {
            return;
        }
        CostCenterBudget budget = lockBudget(advance);
        BigDecimal allocated = nz(budget.getCashAdvanceAllocated());
        if (allocated.compareTo(amount) < 0) {
            throw new BusinessRuleViolationException(
                    "Repayment " + amount + " exceeds the cash advance allocation of " + allocated
                            + " held in the " + budget.getFiscalYear() + " budget. Nothing was changed.");
        }
        budget.setCashAdvanceAllocated(allocated.subtract(amount));
        budget.setAvailableBudget(nz(budget.getAvailableBudget()).add(amount));
        costCenterBudgetRepository.save(budget);
        log.info("Repayment {} released to available budget for advance {} ({})",
                amount, advance.getAdvanceId(), budget.getFiscalYear());
    }

    /** Undoes an offset that has been deleted: the amount goes back into the allocation. */
    public void reverseOffset(CashAdvance advance, BigDecimal amount) {
        reverseOffset(advance, amount, null);
    }

    /**
     * Undoes an offset that has been deleted, mirroring {@link #moveAllocationToUtilized(CashAdvance, BigDecimal,
     * ExpenseReport)}: the whole amount goes back into the allocation, and the shares that were charged to other
     * cost centers are handed back to them and taken out of the advance's available budget again.
     */
    public void reverseOffset(CashAdvance advance, BigDecimal amount, ExpenseReport report) {
        if (!isChargedToBudget(advance) || amount == null || amount.signum() <= 0) {
            return;
        }
        List<ReportCostCenterShares.Share> elsewhere = sharesElsewhere(advance, amount, report);
        Map<UUID, CostCenterBudget> locked = lockBudgets(advance, elsewhere, report);
        CostCenterBudget budget = locked.get(advance.getCostCenter().getCostCenterId());
        BigDecimal movedElsewhere = total(elsewhere);
        budget.setCashAdvanceAllocated(nz(budget.getCashAdvanceAllocated()).add(amount));
        budget.setAvailableBudget(nz(budget.getAvailableBudget()).subtract(movedElsewhere));
        costCenterBudgetRepository.save(budget);
        for (ReportCostCenterShares.Share share : elsewhere) {
            adjustAvailable(locked.get(share.costCenter().getCostCenterId()), share, share.amount());
        }
        log.info("Deleted offset {} restored to allocation for advance {} ({}), {} of it returned to other cost centers",
                amount, advance.getAdvanceId(), budget.getFiscalYear(), movedElsewhere);
    }

    static boolean isChargedToBudget(CashAdvance advance) {
        return advance != null && advance.getBudgetFiscalYear() != null && advance.getCostCenter() != null;
    }

    /** The parts of {@code amount} the report allocates to cost centers other than the advance's own. */
    private static List<ReportCostCenterShares.Share> sharesElsewhere(CashAdvance advance, BigDecimal amount, ExpenseReport report) {
        if (report == null) {
            return List.of();
        }
        UUID ownCostCenterId = advance.getCostCenter().getCostCenterId();
        return ReportCostCenterShares.distribute(amount, report).stream()
                .filter(share -> !share.costCenter().getCostCenterId().equals(ownCostCenterId))
                .toList();
    }

    /**
     * Locks the advance's budget row and the row of every other cost center the offset touches, in cost
     * center id order so two offsets over overlapping cost centers can't deadlock. The advance's own row must
     * exist; another cost center with no budget row (unbudgeted spending) is simply absent from the map.
     */
    private Map<UUID, CostCenterBudget> lockBudgets(CashAdvance advance, List<ReportCostCenterShares.Share> elsewhere,
                                                    ExpenseReport report) {
        UUID ownCostCenterId = advance.getCostCenter().getCostCenterId();
        String otherFiscalYear = report != null && report.getFiscalYear() != null ? report.getFiscalYear() : advance.getBudgetFiscalYear();
        Map<UUID, String> fiscalYearByCostCenter = new TreeMap<>();
        fiscalYearByCostCenter.put(ownCostCenterId, advance.getBudgetFiscalYear());
        elsewhere.forEach(share -> fiscalYearByCostCenter.put(share.costCenter().getCostCenterId(), otherFiscalYear));

        Map<UUID, CostCenterBudget> locked = new HashMap<>();
        fiscalYearByCostCenter.forEach((costCenterId, fiscalYear) -> costCenterBudgetRepository
                .findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, fiscalYear)
                .ifPresent(budget -> locked.put(costCenterId, budget)));
        if (!locked.containsKey(ownCostCenterId)) {
            throw new BusinessRuleViolationException(
                    "No budget row exists for the cash advance's cost center and fiscal year "
                            + advance.getBudgetFiscalYear() + ".");
        }
        return locked;
    }

    private void adjustAvailable(CostCenterBudget budget, ReportCostCenterShares.Share share, BigDecimal delta) {
        if (budget == null) {
            log.warn("No budget configured for cost center {} - its {} share of the offset is not tracked against a budget",
                    share.costCenter().getCostCenterCode(), share.amount());
            return;
        }
        budget.setAvailableBudget(nz(budget.getAvailableBudget()).add(delta));
        costCenterBudgetRepository.save(budget);
    }

    private static BigDecimal total(List<ReportCostCenterShares.Share> shares) {
        return shares.stream().map(ReportCostCenterShares.Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CostCenterBudget lockBudget(CashAdvance advance) {
        return costCenterBudgetRepository
                .findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                        advance.getCostCenter().getCostCenterId(), advance.getBudgetFiscalYear())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "No budget row exists for the cash advance's cost center and fiscal year "
                                + advance.getBudgetFiscalYear() + "."));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
