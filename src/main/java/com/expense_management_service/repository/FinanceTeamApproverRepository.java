package com.expense_management_service.repository;

import com.expense_management_service.entity.FinanceTeamApprover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinanceTeamApproverRepository extends JpaRepository<FinanceTeamApprover, UUID> {

    Optional<FinanceTeamApprover> findByCostCenter_CostCenterId(UUID costCenterId);
}
