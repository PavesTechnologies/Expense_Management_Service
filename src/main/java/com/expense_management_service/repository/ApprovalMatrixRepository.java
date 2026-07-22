package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalMatrix;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalMatrixRepository extends JpaRepository<ApprovalMatrix, UUID> {
}
