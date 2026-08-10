package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyRequest;
import com.expense_management_service.dto.response.PolicyResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.mapper.PolicyMapper;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.service.PolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyServiceImplTest {

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private PolicyRuleRepository policyRuleRepository;
    @Mock
    private PolicyAssignmentRepository policyAssignmentRepository;
    @Mock
    private PolicyVersionService policyVersionService;

    private PolicyServiceImpl policyService;

    @BeforeEach
    void setUp() {
        policyService = new PolicyServiceImpl(policyRepository, policyRuleRepository, policyAssignmentRepository,
                policyVersionService, new PolicyMapper());
    }

    @Test
    void create_savesPolicy_whenNameNotTaken() {
        when(policyRepository.findByPolicyName("Field Sales Policy")).thenReturn(Optional.empty());
        when(policyRepository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyVersionService.getCurrentVersion(any())).thenReturn(1);

        PolicyResponse response = policyService.create(new PolicyRequest("Field Sales Policy", "desc", "ACTIVE"));

        assertThat(response.policyName()).isEqualTo("Field Sales Policy");
        assertThat(response.currentVersion()).isEqualTo(1);
    }

    @Test
    void create_throwsDuplicateResource_whenNameAlreadyTaken() {
        Policy existing = Policy.builder().policyId(UUID.randomUUID()).policyName("Field Sales Policy").build();
        when(policyRepository.findByPolicyName("Field Sales Policy")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> policyService.create(new PolicyRequest("Field Sales Policy", null, "ACTIVE")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(policyRepository, never()).save(any());
    }

    @Test
    void update_doesNotBumpVersion_becauseMetadataOnlyChangesDontAffectEvaluation() {
        UUID policyId = UUID.randomUUID();
        Policy existing = Policy.builder().policyId(policyId).policyName("Field Sales Policy").status("ACTIVE").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(policyRepository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        policyService.update(policyId, new PolicyRequest("Field Sales Policy", "updated description", "ACTIVE"));

        verify(policyVersionService, never()).activateNewVersion(any());
    }

    @Test
    void delete_throwsResourceInUse_whenPolicyStillHasRules() {
        UUID policyId = UUID.randomUUID();
        Policy existing = Policy.builder().policyId(policyId).policyName("Field Sales Policy").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(policyRuleRepository.existsByPolicy_PolicyId(policyId)).thenReturn(true);

        assertThatThrownBy(() -> policyService.delete(policyId)).isInstanceOf(ResourceInUseException.class);

        verify(policyRepository, never()).delete(any());
    }

    @Test
    void delete_throwsResourceInUse_whenPolicyStillAssigned() {
        UUID policyId = UUID.randomUUID();
        Policy existing = Policy.builder().policyId(policyId).policyName("Default Policy").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(policyRuleRepository.existsByPolicy_PolicyId(policyId)).thenReturn(false);
        when(policyAssignmentRepository.existsByPolicy_PolicyId(policyId)).thenReturn(true);

        // This is exactly what keeps an admin from ever deleting the seeded Default Policy -
        // it always has an active DEFAULT assignment pointing to it.
        assertThatThrownBy(() -> policyService.delete(policyId)).isInstanceOf(ResourceInUseException.class);

        verify(policyRepository, never()).delete(any());
    }

    @Test
    void delete_succeeds_whenPolicyHasNoRulesAndNoAssignment() {
        UUID policyId = UUID.randomUUID();
        Policy existing = Policy.builder().policyId(policyId).policyName("Unused Policy").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(policyRuleRepository.existsByPolicy_PolicyId(policyId)).thenReturn(false);
        when(policyAssignmentRepository.existsByPolicy_PolicyId(policyId)).thenReturn(false);

        policyService.delete(policyId);

        verify(policyRepository).delete(existing);
    }

    @Test
    void getVersionHistory_throwsResourceNotFound_whenPolicyMissing() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.getVersionHistory(policyId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
