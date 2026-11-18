package com.expense_management_service.repository;

import com.expense_management_service.entity.SupplementaryClaimItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupplementaryClaimItemRepository extends JpaRepository<SupplementaryClaimItem, UUID> {

    List<SupplementaryClaimItem> findByClaim_ClaimId(UUID claimId);
}
