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

    @Override
    public List<ExpenseLineItem> addReviewsForNewLines(ApprovalLevelInstance instance, List<ExpenseLineItem> lineItems) {
        java.util.Set<java.util.UUID> reviewed = new java.util.HashSet<>();
        approvalLineItemReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId())
                .forEach(review -> reviewed.add(review.getLineItem().getLineItemId()));
        List<ExpenseLineItem> added = lineItems.stream().filter(line -> !reviewed.contains(line.getLineItemId())).toList();
        createPendingReviews(instance, added);
        return added;
    }

    @Override
    public List<ExpenseLineItem> reopenReviewsForRevisedLines(ApprovalLevelInstance instance) {
        List<ExpenseLineItem> reopened = new java.util.ArrayList<>();
        approvalLineItemReviewRepository.findByLevelInstance_InstanceIdAndStatus(
                        instance.getInstanceId(), LineItemReviewStatus.APPROVED)
                .forEach(review -> {
                    var line = review.getLineItem();
                    if (line != null && line.getRevisedAt() != null
                            && (review.getActionedAt() == null || line.getRevisedAt().isAfter(review.getActionedAt()))) {
                        review.setStatus(LineItemReviewStatus.PENDING);
                        approvalLineItemReviewRepository.save(review);
                        reopened.add(line);
                    }
                });
        return reopened;
    }
}
