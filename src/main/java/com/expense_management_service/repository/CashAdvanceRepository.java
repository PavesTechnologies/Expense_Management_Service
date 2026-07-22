package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CashAdvanceRepository extends JpaRepository<CashAdvance, UUID> {
}
