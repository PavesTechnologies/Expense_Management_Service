package com.expense_management_service.repository;

import com.expense_management_service.entity.CostCenter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CostCenterRepository extends JpaRepository<CostCenter, UUID> {
}
