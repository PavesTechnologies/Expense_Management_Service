package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CashAdvanceFeeRepository extends JpaRepository<CashAdvanceFee, UUID> {

    boolean existsByAdvance_AdvanceId(UUID advanceId);

    Optional<CashAdvanceFee> findByAdvance_AdvanceId(UUID advanceId);
}
