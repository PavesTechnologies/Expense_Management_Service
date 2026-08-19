package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import com.expense_management_service.repository.VerificationQueryRepository;
import com.expense_management_service.service.LevelReviewStrategy;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The {@code LevelType.FINANCE_VERIFICATION} strategy - the Finance-level twin of {@code
 * ApprovalReviewStrategy}, backed by {@code FinanceVerificationReview} instead of {@code
 * ApprovalLineItemReview}. A level is complete only once every line item is VERIFIED - a QUERIED
 * line (non-terminal) blocks completion exactly like a NEEDS_CORRECTION line does on the Manager
 * side, without disturbing sibling lines already VERIFIED.
 */
@Component
@RequiredArgsConstructor
public class FinanceVerificationStrategy implements LevelReviewStrategy {

    /** {@code VerificationQuery.status} is a plain, unenumerated String (pre-existing entity, see class-level note in {@code VerificationQuery}) - these are the only two values Finance Verification ever writes to it. */
    public static final String QUERY_STATUS_RAISED = "RAISED";
    public static final String QUERY_STATUS_RESOLVED = "RESOLVED";

    private final FinanceVerificationReviewRepository financeVerificationReviewRepository;
    private final VerificationQueryRepository verificationQueryRepository;

    @Override
    public LevelType levelType() {
        return LevelType.FINANCE_VERIFICATION;
    }

    @Override
    public void createPendingReviews(ApprovalLevelInstance instance, List<ExpenseLineItem> lineItems) {
        for (ExpenseLineItem lineItem : lineItems) {
            financeVerificationReviewRepository.save(FinanceVerificationReview.builder()
                    .lineItem(lineItem)
                    .levelInstance(instance)
                    .status(FinanceVerificationStatus.PENDING)
                    .build());
        }
    }

    @Override
    public boolean isLevelComplete(ApprovalLevelInstance instance) {
        return financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .allMatch(r -> r.getStatus() == FinanceVerificationStatus.VERIFIED);
    }

    @Override
    public void resetPendingReviews(ApprovalLevelInstance instance) {
        financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId())
                .forEach(review -> {
                    review.setStatus(FinanceVerificationStatus.PENDING);
                    financeVerificationReviewRepository.save(review);
                });
    }

    @Override
    public void resumeCorrectedReviews(ApprovalLevelInstance instance) {
        financeVerificationReviewRepository.findByLevelInstance_InstanceIdAndStatus(
                        instance.getInstanceId(), FinanceVerificationStatus.QUERIED)
                .forEach(review -> {
                    review.setStatus(FinanceVerificationStatus.PENDING);
                    financeVerificationReviewRepository.save(review);
                });

        verificationQueryRepository.findByLevelInstance_InstanceIdAndStatus(instance.getInstanceId(), QUERY_STATUS_RAISED)
                .forEach(query -> {
                    query.setStatus(QUERY_STATUS_RESOLVED);
                    query.setResolvedAt(LocalDateTime.now());
                    verificationQueryRepository.save(query);
                });
    }
}
