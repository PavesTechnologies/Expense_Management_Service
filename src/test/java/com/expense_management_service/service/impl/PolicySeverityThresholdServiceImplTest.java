package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicySeverityThresholdRequest;
import com.expense_management_service.dto.response.PolicySeverityThresholdResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicySeverityThreshold;
import com.expense_management_service.enums.PolicyOverageTier;
import com.expense_management_service.mapper.PolicySeverityThresholdMapper;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.repository.PolicySeverityThresholdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicySeverityThresholdServiceImplTest {

    @Mock
    private PolicySeverityThresholdRepository policySeverityThresholdRepository;
    @Mock
    private PolicyRepository policyRepository;

    private PolicySeverityThresholdServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PolicySeverityThresholdServiceImpl(policySeverityThresholdRepository, policyRepository, new PolicySeverityThresholdMapper());
    }

    private PolicySeverityThreshold band(Policy policy, PolicyOverageTier tier, String min, String max) {
        return PolicySeverityThreshold.builder().thresholdId(UUID.randomUUID()).policy(policy).tier(tier)
                .minPercentOver(new BigDecimal(min)).maxPercentOver(max != null ? new BigDecimal(max) : null).build();
    }

    @Test
    void getForScope_returnsGlobalDefaults_whenPolicyIdNull() {
        PolicySeverityThreshold global = band(null, PolicyOverageTier.SEVERE, "60", null);
        when(policySeverityThresholdRepository.findByPolicyIsNullOrderByMinPercentOverAsc()).thenReturn(List.of(global));

        List<PolicySeverityThresholdResponse> responses = service.getForScope(null);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).tier()).isEqualTo(PolicyOverageTier.SEVERE);
        assertThat(responses.get(0).policyId()).isNull();
    }

    @Test
    void getForScope_returnsPolicySpecificBands_whenPolicyIdProvided() {
        UUID policyId = UUID.randomUUID();
        Policy policy = Policy.builder().policyId(policyId).policyName("Field Sales Policy").status("ACTIVE").build();
        PolicySeverityThreshold scoped = band(policy, PolicyOverageTier.MINOR, "0", "30");
        when(policySeverityThresholdRepository.findByPolicy_PolicyIdOrderByMinPercentOverAsc(policyId)).thenReturn(List.of(scoped));

        List<PolicySeverityThresholdResponse> responses = service.getForScope(policyId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).policyId()).isEqualTo(policyId);
    }

    @Test
    void replaceForScope_replacesGlobalDefaults_whenPolicyIdNull() {
        when(policySeverityThresholdRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        List<PolicySeverityThresholdRequest> requests = List.of(
                new PolicySeverityThresholdRequest(PolicyOverageTier.MINOR, BigDecimal.ZERO, new BigDecimal("30")),
                new PolicySeverityThresholdRequest(PolicyOverageTier.MODERATE, new BigDecimal("30"), new BigDecimal("60")),
                new PolicySeverityThresholdRequest(PolicyOverageTier.SEVERE, new BigDecimal("60"), null));

        List<PolicySeverityThresholdResponse> responses = service.replaceForScope(null, requests);

        verify(policySeverityThresholdRepository).deleteByPolicyIsNull();
        verify(policyRepository, never()).findById(any());
        assertThat(responses).hasSize(3);
        assertThat(responses).allMatch(r -> r.policyId() == null);
    }

    @Test
    void replaceForScope_replacesPolicySpecificBands_whenPolicyIdProvided() {
        UUID policyId = UUID.randomUUID();
        Policy policy = Policy.builder().policyId(policyId).policyName("Field Sales Policy").status("ACTIVE").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policySeverityThresholdRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<PolicySeverityThresholdResponse> responses = service.replaceForScope(policyId,
                List.of(new PolicySeverityThresholdRequest(PolicyOverageTier.SEVERE, BigDecimal.ZERO, null)));

        verify(policySeverityThresholdRepository).deleteByPolicy_PolicyId(policyId);
        assertThat(responses.get(0).policyId()).isEqualTo(policyId);
    }

    @Test
    void replaceForScope_throwsResourceNotFound_whenPolicyMissing() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceForScope(policyId,
                List.of(new PolicySeverityThresholdRequest(PolicyOverageTier.SEVERE, BigDecimal.ZERO, null))))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(policySeverityThresholdRepository, never()).saveAll(any());
    }

    @Test
    void replaceForScope_throwsIllegalArgument_whenMaxNotGreaterThanMin() {
        List<PolicySeverityThresholdRequest> requests = List.of(
                new PolicySeverityThresholdRequest(PolicyOverageTier.MINOR, new BigDecimal("30"), new BigDecimal("20")));

        assertThatThrownBy(() -> service.replaceForScope(null, requests))
                .isInstanceOf(IllegalArgumentException.class);

        verify(policySeverityThresholdRepository, never()).saveAll(any());
    }
}
