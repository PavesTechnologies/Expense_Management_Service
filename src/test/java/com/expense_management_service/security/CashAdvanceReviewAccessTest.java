package com.expense_management_service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.service.DelegationService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reporting Manager access to one cash advance: only when the advance's manager is the caller or someone who has
 * delegated their approvals to the caller.
 */
class CashAdvanceReviewAccessTest {

    private static final String RM = "5100099";
    private static final String ASSIGNED_MANAGER = "5100050";
    private static final String OTHER_MANAGER = "5100060";

    private CashAdvanceRepository repository;
    private DelegationService delegation;
    private CurrentUserService currentUser;
    private CashAdvanceReviewAccess access;
    private final UUID advanceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(CashAdvanceRepository.class);
        delegation = mock(DelegationService.class);
        currentUser = mock(CurrentUserService.class);
        access = new CashAdvanceReviewAccess(repository, delegation, currentUser);
        when(currentUser.getEmployeeId()).thenReturn(RM);
        when(delegation.resolveApproverIdsActingFor(RM)).thenReturn(Set.of(RM));
    }

    @Test
    void assignedManagerIsAllowed() {
        givenAdvanceManagedBy(RM);

        assertThat(access.isAssignedApprover(advanceId)).isTrue();
    }

    @Test
    void managerWhoDelegatedToTheCallerIsAllowed() {
        when(delegation.resolveApproverIdsActingFor(RM)).thenReturn(Set.of(RM, ASSIGNED_MANAGER));
        givenAdvanceManagedBy(ASSIGNED_MANAGER);

        assertThat(access.isAssignedApprover(advanceId)).isTrue();
    }

    @Test
    void unassignedReportingManagerIsDenied() {
        givenAdvanceManagedBy(OTHER_MANAGER);

        assertThat(access.isAssignedApprover(advanceId)).isFalse();
    }

    @Test
    void unknownAdvanceIsDenied() {
        when(repository.findById(advanceId)).thenReturn(Optional.empty());

        assertThat(access.isAssignedApprover(advanceId)).isFalse();
    }

    @Test
    void advanceWithoutManagerIsDenied() {
        givenAdvanceManagedBy("  ");

        assertThat(access.isAssignedApprover(advanceId)).isFalse();
    }

    @Test
    void missingCallerOrAdvanceIdIsDeniedWithoutLookup() {
        when(currentUser.getEmployeeId()).thenReturn(null);
        assertThat(access.isAssignedApprover(advanceId)).isFalse();

        assertThat(access.isAssignedApprover(null)).isFalse();
        verifyNoInteractions(repository);
    }

    private void givenAdvanceManagedBy(String managerId) {
        CashAdvance advance = mock(CashAdvance.class);
        when(advance.getManagerId()).thenReturn(managerId);
        when(repository.findById(any(UUID.class))).thenReturn(Optional.of(advance));
    }
}
