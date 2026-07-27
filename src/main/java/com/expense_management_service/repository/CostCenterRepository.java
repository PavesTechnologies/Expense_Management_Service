package com.expense_management_service.repository;

import com.expense_management_service.entity.CostCenter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CostCenterRepository extends JpaRepository<CostCenter, UUID> {

    /** Duplicate-code validation — cost center code is unique across the whole table. */
    Optional<CostCenter> findByCostCenterCodeIgnoreCase(String costCenterCode);

    /** Duplicate-name validation — cost center name only needs to be unique within its own department. */
    Optional<CostCenter> findByCostCenterNameIgnoreCaseAndDepartmentUuid(String costCenterName, UUID departmentUuid);
}
