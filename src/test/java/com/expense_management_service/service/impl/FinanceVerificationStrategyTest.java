package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceVerificationStrategyTest {

    @Mock private FinanceVerificationReviewRepository financeVerificationReviewRepository;
    @Mock private com.expense_management_service.repository.VerificationQueryRepository verificationQueryRepository;

    private FinanceVerificationStrategy strategy;
    private final ApprovalLevelInstance instance = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        strategy = new FinanceVerificationStrategy(financeVerificationReviewRepository, verificationQueryRepository);
    }

    @Test
    void levelType_isFinanceVerification() {
        assertThat(strategy.levelType()).isEqualTo(LevelType.FINANCE_VERIFICATION);
    }

    @Test
    void createPendingReviews_savesOnePendingReviewPerLineItem() {
        ExpenseLineItem a = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        ExpenseLineItem b = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        when(financeVerificationReviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        strategy.createPendingReviews(instance, List.of(a, b));

        verify(financeVerificationReviewRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void isLevelComplete_true_onlyWhenEveryReviewVerified() {
        FinanceVerificationReview verified = FinanceVerificationReview.builder().status(FinanceVerificationStatus.VERIFIED).build();
        FinanceVerificationReview pending = FinanceVerificationReview.builder().status(FinanceVerificationStatus.PENDING).build();
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()))
                .thenReturn(List.of(verified, pending));

        assertThat(strategy.isLevelComplete(instance)).isFalse();

        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()))
                .thenReturn(List.of(verified));

        assertThat(strategy.isLevelComplete(instance)).isTrue();
    }

    @Test
    void isLevelComplete_false_whenAQueriedReviewIsStillOpen() {
        FinanceVerificationReview verified = FinanceVerificationReview.builder().status(FinanceVerificationStatus.VERIFIED).build();
        FinanceVerificationReview queried = FinanceVerificationReview.builder().status(FinanceVerificationStatus.QUERIED).build();
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()))
                .thenReturn(List.of(verified, queried));

        assertThat(strategy.isLevelComplete(instance)).isFalse();
    }

    @Test
    void resetPendingReviews_resetsEveryReviewBackToPending() {
        FinanceVerificationReview verified = FinanceVerificationReview.builder().status(FinanceVerificationStatus.VERIFIED).build();
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()))
                .thenReturn(List.of(verified));
        when(financeVerificationReviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        strategy.resetPendingReviews(instance);

        assertThat(verified.getStatus()).isEqualTo(FinanceVerificationStatus.PENDING);
    }

    @Test
    void resumeCorrectedReviews_resetsOnlyQueriedReviews_andResolvesOpenVerificationQueries() {
        FinanceVerificationReview verified = FinanceVerificationReview.builder().status(FinanceVerificationStatus.VERIFIED).build();
        FinanceVerificationReview queried = FinanceVerificationReview.builder().status(FinanceVerificationStatus.QUERIED).build();
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceIdAndStatus(instance.getInstanceId(), FinanceVerificationStatus.QUERIED))
                .thenReturn(List.of(queried));
        when(financeVerificationReviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.expense_management_service.entity.VerificationQuery openQuery = com.expense_management_service.entity.VerificationQuery.builder().build();
        when(verificationQueryRepository.findByLevelInstance_InstanceIdAndStatus(instance.getInstanceId(), FinanceVerificationStrategy.QUERY_STATUS_RAISED))
                .thenReturn(List.of(openQuery));
        when(verificationQueryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        strategy.resumeCorrectedReviews(instance);

        assertThat(queried.getStatus()).isEqualTo(FinanceVerificationStatus.PENDING);
        assertThat(verified.getStatus()).isEqualTo(FinanceVerificationStatus.VERIFIED);
        assertThat(openQuery.getStatus()).isEqualTo(FinanceVerificationStrategy.QUERY_STATUS_RESOLVED);
        assertThat(openQuery.getResolvedAt()).isNotNull();
    }
}
