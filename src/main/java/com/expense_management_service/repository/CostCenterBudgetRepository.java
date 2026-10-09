package com.expense_management_service.repository;

import com.expense_management_service.entity.CostCenterBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CostCenterBudgetRepository extends JpaRepository<CostCenterBudget, UUID> {

    Optional<CostCenterBudget> findByCostCenter_CostCenterIdAndFiscalYear(
            UUID costCenterId,
            String fiscalYear
    );

    Optional<CostCenterBudget> findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
            UUID costCenterId,
            String fiscalYear
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CostCenterBudget b "
            + "where b.costCenter.costCenterId = :costCenterId "
            + "and lower(b.fiscalYear) = lower(:fiscalYear)")
    Optional<CostCenterBudget> findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
            @Param("costCenterId") UUID costCenterId,
            @Param("fiscalYear") String fiscalYear
    );
}
