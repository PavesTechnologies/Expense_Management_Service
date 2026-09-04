package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalMatrix;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalMatrixRepository extends JpaRepository<ApprovalMatrix, UUID> {

    List<ApprovalMatrix> findByCostCenter_CostCenterIdAndStatusOrderByApprovalLevelAsc(UUID costCenterId, String status);
}
