package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceRepayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CashAdvanceRepaymentRepository extends JpaRepository<CashAdvanceRepayment, UUID> {
    List<CashAdvanceRepayment> findByCashAdvance_AdvanceId(UUID advanceId);
}
