package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.service.LevelReviewStrategy;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The {@code LevelType.APPROVAL} strategy - a pure extraction of {@code
 * ApprovalWorkflowServiceImpl}'s pre-Finance-Verification review logic onto {@code
 * ApprovalLineItemReview}. Deliberately behavior-identical to what the engine already did; no
 * functional change to Manager approval.
 */
@Component
@RequiredArgsConstructor
public class ApprovalReviewStrategy implements LevelReviewStrategy {

    private final ApprovalLineItemReviewRepository approvalLineItemReviewRepository;

    @Override
    public LevelType levelType() {
        return LevelType.APPROVAL;
    }

    @Override
    public void createPendingReviews(ApprovalLevelInstance instance, List<ExpenseLineItem> lineItems) {
        for (ExpenseLineItem lineItem : lineItems) {
            approvalLineItemReviewRepository.save(ApprovalLineItemReview.builder()
                    .lineItem(lineItem)
                    .levelInstance(instance)
                    .status(LineItemReviewStatus.PENDING)
                    .build());
        }
    }

    @Override
    public boolean isLevelComplete(ApprovalLevelInstance instance) {
        return approvalLineItemReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED);
    }

    @Override
    public void resetPendingReviews(ApprovalLevelInstance instance) {
        approvalLineItemReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId())
                .forEach(review -> {
                    review.setStatus(LineItemReviewStatus.PENDING);
                    approvalLineItemReviewRepository.save(review);
                });
    }

    @Override
    public void resumeCorrectedReviews(ApprovalLevelInstance instance) {
        approvalLineItemReviewRepository.findByLevelInstance_InstanceIdAndStatus(
                        instance.getInstanceId(), LineItemReviewStatus.NEEDS_CORRECTION)
                .forEach(review -> {
                    review.setStatus(LineItemReviewStatus.PENDING);
                    approvalLineItemReviewRepository.save(review);
                });
    }
}
