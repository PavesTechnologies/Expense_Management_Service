package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.PolicyVersionResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyVersion;
import com.expense_management_service.repository.PolicyVersionRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyVersionServiceImplTest {

    @Mock
    private PolicyVersionRepository policyVersionRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CurrentUserService currentUserService;

    private PolicyVersionServiceImpl service;

    private UUID policyId;
    private Policy policy;

    @BeforeEach
    void setUp() {
        service = new PolicyVersionServiceImpl(policyVersionRepository, auditLogService, currentUserService);
        policyId = UUID.randomUUID();
        policy = Policy.builder().policyId(policyId).policyName("Field Sales Policy").status("ACTIVE").build();
    }

    @Test
    void getCurrentVersion_returnsOne_whenPolicyHasNeverBeenVersioned() {
        when(policyVersionRepository.findTopByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)).thenReturn(Optional.empty());

        assertThat(service.getCurrentVersion(policyId)).isEqualTo(1);
    }

    @Test
    void getCurrentVersion_returnsHighestLoggedVersionNumber() {
        PolicyVersion v3 = PolicyVersion.builder().versionId(UUID.randomUUID()).policy(policy).versionNumber(3).activatedAt(LocalDateTime.now()).build();
        when(policyVersionRepository.findTopByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)).thenReturn(Optional.of(v3));

        assertThat(service.getCurrentVersion(policyId)).isEqualTo(3);
    }

    @Test
    void activateNewVersion_bumpsFromOneToTwo_forANeverVersionedPolicy() {
        when(policyVersionRepository.findTopByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)).thenReturn(Optional.empty());
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(UUID.randomUUID(), "admin-1", null, null, null, null));

        int newVersion = service.activateNewVersion(policy);

        assertThat(newVersion).isEqualTo(2);
        verify(policyVersionRepository).save(any(PolicyVersion.class));
    }

    @Test
    void activateNewVersion_bumpsFromThreeToFour_whenAlreadyVersioned() {
        PolicyVersion v3 = PolicyVersion.builder().versionId(UUID.randomUUID()).policy(policy).versionNumber(3).activatedAt(LocalDateTime.now()).build();
        when(policyVersionRepository.findTopByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)).thenReturn(Optional.of(v3));
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(UUID.randomUUID(), "admin-1", null, null, null, null));

        assertThat(service.activateNewVersion(policy)).isEqualTo(4);
    }

    @Test
    void activateNewVersion_recordsAnAuditLogEntry() {
        when(policyVersionRepository.findTopByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)).thenReturn(Optional.empty());
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(UUID.randomUUID(), "admin-1", null, null, null, null));

        service.activateNewVersion(policy);

        verify(auditLogService).create(any());
    }

    @Test
    void getVersionHistory_returnsDescendingList() {
        PolicyVersion v2 = PolicyVersion.builder().versionId(UUID.randomUUID()).policy(policy).versionNumber(2).activatedAt(LocalDateTime.now()).build();
        PolicyVersion v1 = PolicyVersion.builder().versionId(UUID.randomUUID()).policy(policy).versionNumber(1).activatedAt(LocalDateTime.now().minusDays(1)).build();
        when(policyVersionRepository.findByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)).thenReturn(List.of(v2, v1));

        List<PolicyVersionResponse> history = service.getVersionHistory(policyId);

        assertThat(history).extracting(PolicyVersionResponse::versionNumber).containsExactly(2, 1);
    }
}
