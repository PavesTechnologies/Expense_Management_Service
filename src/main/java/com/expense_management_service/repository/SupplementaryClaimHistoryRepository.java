package com.expense_management_service.repository;

import com.expense_management_service.entity.SupplementaryClaimHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupplementaryClaimHistoryRepository extends JpaRepository<SupplementaryClaimHistory, UUID> {

    List<SupplementaryClaimHistory> findByClaim_ClaimIdOrderByCreatedAtAsc(UUID claimId);
}
