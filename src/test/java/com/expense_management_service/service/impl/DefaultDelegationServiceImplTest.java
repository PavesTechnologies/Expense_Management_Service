package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.enums.DelegationStatus;
import com.expense_management_service.repository.ApprovalDelegationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDelegationServiceImplTest {

    @Mock
    private ApprovalDelegationRepository approvalDelegationRepository;

    private DefaultDelegationServiceImpl delegationService;

    @BeforeEach
    void setUp() {
        delegationService = new DefaultDelegationServiceImpl(approvalDelegationRepository);
    }

    private ApprovalDelegation delegation(String delegateId, LocalDate start, LocalDate end, LocalDateTime createdAt) {
        return ApprovalDelegation.builder()
                .delegatorId("mgr-jane")
                .delegateId(delegateId)
                .startDate(start)
                .endDate(end)
                .status(DelegationStatus.ACTIVE)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void canAct_returnsTrue_whenActingUserIsTheApproverThemselves() {
        boolean result = delegationService.canAct("mgr-jane", "mgr-jane");

        assertThat(result).isTrue();
    }

    @Test
    void canAct_returnsTrue_whenActingUserIsActiveDelegateWithinWindow() {
        LocalDate today = LocalDate.now();
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of(delegation("mgr-alex", today.minusDays(1), today.plusDays(5), LocalDateTime.now())));

        boolean result = delegationService.canAct("mgr-alex", "mgr-jane");

        assertThat(result).isTrue();
    }

    @Test
    void canAct_returnsFalse_whenDelegateWindowHasExpired() {
        LocalDate today = LocalDate.now();
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of(delegation("mgr-alex", today.minusDays(10), today.minusDays(1), LocalDateTime.now())));

        boolean result = delegationService.canAct("mgr-alex", "mgr-jane");

        assertThat(result).isFalse();
    }

    @Test
    void canAct_returnsFalse_whenDelegateWindowHasNotStartedYet() {
        LocalDate today = LocalDate.now();
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of(delegation("mgr-alex", today.plusDays(1), today.plusDays(10), LocalDateTime.now())));

        boolean result = delegationService.canAct("mgr-alex", "mgr-jane");

        assertThat(result).isFalse();
    }

    @Test
    void canAct_excludesCancelledDelegations_viaRepositoryQuery() {
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of());

        delegationService.canAct("mgr-alex", "mgr-jane");

        verify(approvalDelegationRepository).findByDelegatorIdAndStatusNot(eq("mgr-jane"), eq(DelegationStatus.CANCELLED));
    }

    @Test
    void canAct_picksLaterCreatedDelegation_whenTwoOverlapToday() {
        LocalDate today = LocalDate.now();
        ApprovalDelegation earlier = delegation("mgr-alex", today.minusDays(5), today.plusDays(5),
                LocalDateTime.now().minusDays(10));
        ApprovalDelegation later = delegation("mgr-sam", today.minusDays(2), today.plusDays(2),
                LocalDateTime.now().minusDays(1));
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of(earlier, later));

        assertThat(delegationService.canAct("mgr-sam", "mgr-jane")).isTrue();
        assertThat(delegationService.canAct("mgr-alex", "mgr-jane")).isFalse();
    }

    @Test
    void canAct_returnsFalse_whenNoDelegationExistsForApprover() {
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of());

        boolean result = delegationService.canAct("mgr-alex", "mgr-jane");

        assertThat(result).isFalse();
    }

    @Test
    void canAct_returnsFalse_whenEitherArgumentIsNull() {
        assertThat(delegationService.canAct(null, "mgr-jane")).isFalse();
        assertThat(delegationService.canAct("mgr-alex", null)).isFalse();
    }

    // ---- resolveActiveDelegate() - used directly by EscalationService ----

    @Test
    void resolveActiveDelegate_returnsTheActiveDelegateId() {
        LocalDate today = LocalDate.now();
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of(delegation("mgr-alex", today.minusDays(1), today.plusDays(5), LocalDateTime.now())));

        assertThat(delegationService.resolveActiveDelegate("mgr-jane")).contains("mgr-alex");
    }

    @Test
    void resolveActiveDelegate_returnsEmpty_whenNoDelegationIsCurrentlyInEffect() {
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot("mgr-jane", DelegationStatus.CANCELLED))
                .thenReturn(List.of());

        assertThat(delegationService.resolveActiveDelegate("mgr-jane")).isEmpty();
    }
}
