package com.expense_management_service.repository;

import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.enums.FinanceVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceVerificationReviewRepository extends JpaRepository<FinanceVerificationReview, UUID> {

    List<FinanceVerificationReview> findByLevelInstance_InstanceId(UUID instanceId);

    List<FinanceVerificationReview> findByLevelInstance_InstanceIdAndStatus(UUID instanceId, FinanceVerificationStatus status);

    Optional<FinanceVerificationReview> findByLineItem_LineItemIdAndLevelInstance_InstanceId(UUID lineItemId, UUID instanceId);
}
