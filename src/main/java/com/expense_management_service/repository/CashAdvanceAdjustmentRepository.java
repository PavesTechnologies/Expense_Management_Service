package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CashAdvanceAdjustmentRepository extends JpaRepository<CashAdvanceAdjustment, UUID> {
}
