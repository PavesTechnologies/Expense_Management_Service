package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.enums.LineItemReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalLineItemReviewRepository extends JpaRepository<ApprovalLineItemReview, UUID> {

    List<ApprovalLineItemReview> findByLevelInstance_InstanceId(UUID instanceId);

    List<ApprovalLineItemReview> findByLevelInstance_InstanceIdAndStatus(UUID instanceId, LineItemReviewStatus status);

    Optional<ApprovalLineItemReview> findByLineItem_LineItemIdAndLevelInstance_InstanceId(UUID lineItemId, UUID instanceId);
}
