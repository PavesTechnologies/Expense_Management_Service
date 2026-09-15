package com.expense_management_service.repository;

import com.expense_management_service.entity.CostCenterBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface CostCenterBudgetRepository extends JpaRepository<CostCenterBudget, UUID> {

    /** Duplicate-fiscal-year validation — a cost center may only have one budget per fiscal year. */
    Optional<CostCenterBudget> findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(UUID costCenterId, String fiscalYear);

    /**
     * Same lookup, held under a pessimistic write lock for the duration of the caller's transaction -
     * the one new concurrency mechanism this codebase didn't previously need (Decision 7). Used only
     * by {@code BudgetEncumbranceServiceImpl.validateAndEncumber}, never by {@code consumeBudget}
     * (which continues to rely on {@code CostCenterBudget.version} exactly as it always has, since it
     * is the only writer of {@code availableBudget}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CostCenterBudget> findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(UUID costCenterId, String fiscalYear);
}
