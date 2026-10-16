package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.enums.LineItemReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalLineItemReviewRepository extends JpaRepository<ApprovalLineItemReview, UUID> {

    List<ApprovalLineItemReview> findByLevelInstance_InstanceId(UUID instanceId);

    /** Every approver review of one line item, across levels and cycles. */
    List<ApprovalLineItemReview> findByLineItem_LineItemId(UUID lineItemId);

    List<ApprovalLineItemReview> findByLevelInstance_InstanceIdAndStatus(UUID instanceId, LineItemReviewStatus status);

    Optional<ApprovalLineItemReview> findByLineItem_LineItemIdAndLevelInstance_InstanceId(UUID lineItemId, UUID instanceId);

    /** Every line-level review on one report, across all approval levels - used for utilization verification status. */
    List<ApprovalLineItemReview> findByLineItem_Report_ReportId(UUID reportId);
}
