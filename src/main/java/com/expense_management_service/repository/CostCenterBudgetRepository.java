package com.expense_management_service.repository;

import com.expense_management_service.entity.CostCenterBudget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CostCenterBudgetRepository extends JpaRepository<CostCenterBudget, UUID> {

    /** Duplicate-fiscal-year validation — a cost center may only have one budget per fiscal year. */
    Optional<CostCenterBudget> findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(UUID costCenterId, String fiscalYear);
}
