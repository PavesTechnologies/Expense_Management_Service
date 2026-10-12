package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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
        if (!isChargedToBudget(advance) || amount == null || amount.signum() <= 0) {
            return;
        }
        CostCenterBudget budget = lockBudget(advance);
        BigDecimal allocated = nz(budget.getCashAdvanceAllocated());
        if (allocated.compareTo(amount) < 0) {
            throw new BusinessRuleViolationException(
                    "Offset " + amount + " exceeds the cash advance allocation of " + allocated
                            + " held in the " + budget.getFiscalYear() + " budget. Nothing was changed.");
        }
        budget.setCashAdvanceAllocated(allocated.subtract(amount));
        costCenterBudgetRepository.save(budget);
        log.info("Offset {} moved from allocation to utilization for advance {} ({})",
                amount, advance.getAdvanceId(), budget.getFiscalYear());
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
        if (!isChargedToBudget(advance) || amount == null || amount.signum() <= 0) {
            return;
        }
        CostCenterBudget budget = lockBudget(advance);
        budget.setCashAdvanceAllocated(nz(budget.getCashAdvanceAllocated()).add(amount));
        costCenterBudgetRepository.save(budget);
        log.info("Deleted offset {} restored to allocation for advance {} ({})",
                amount, advance.getAdvanceId(), budget.getFiscalYear());
    }

    static boolean isChargedToBudget(CashAdvance advance) {
        return advance != null && advance.getBudgetFiscalYear() != null && advance.getCostCenter() != null;
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
