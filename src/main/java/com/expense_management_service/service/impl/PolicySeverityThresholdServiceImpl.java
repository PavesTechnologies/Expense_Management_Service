package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicySeverityThresholdRequest;
import com.expense_management_service.dto.response.PolicySeverityThresholdResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicySeverityThreshold;
import com.expense_management_service.mapper.PolicySeverityThresholdMapper;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.repository.PolicySeverityThresholdRepository;
import com.expense_management_service.service.PolicySeverityThresholdService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PolicySeverityThresholdServiceImpl implements PolicySeverityThresholdService {

    private final PolicySeverityThresholdRepository policySeverityThresholdRepository;
    private final PolicyRepository policyRepository;
    private final PolicySeverityThresholdMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<PolicySeverityThresholdResponse> getForScope(UUID policyId) {
        List<PolicySeverityThreshold> thresholds = policyId != null
                ? policySeverityThresholdRepository.findByPolicy_PolicyIdOrderByMinPercentOverAsc(policyId)
                : policySeverityThresholdRepository.findByPolicyIsNullOrderByMinPercentOverAsc();
        return thresholds.stream().map(mapper::toResponse).toList();
    }

    @Override
    public List<PolicySeverityThresholdResponse> replaceForScope(UUID policyId, List<PolicySeverityThresholdRequest> requests) {
        assertValidBands(requests);
        Policy policy = policyId != null ? findPolicy(policyId) : null;

        if (policyId != null) {
            policySeverityThresholdRepository.deleteByPolicy_PolicyId(policyId);
        } else {
            policySeverityThresholdRepository.deleteByPolicyIsNull();
        }

        List<PolicySeverityThreshold> toSave = requests.stream()
                .map(request -> PolicySeverityThreshold.builder()
                        .policy(policy)
                        .tier(request.tier())
                        .minPercentOver(request.minPercentOver())
                        .maxPercentOver(request.maxPercentOver())
                        .build())
                .toList();
        List<PolicySeverityThreshold> saved = policySeverityThresholdRepository.saveAll(toSave);
        log.info("Replaced severity threshold bands for {} with {} band(s)",
                policyId != null ? "policy " + policyId : "the global default scope", saved.size());
        return saved.stream().map(mapper::toResponse).toList();
    }

    private void assertValidBands(List<PolicySeverityThresholdRequest> requests) {
        for (PolicySeverityThresholdRequest request : requests) {
            if (request.maxPercentOver() != null && request.maxPercentOver().compareTo(request.minPercentOver()) <= 0) {
                throw new IllegalArgumentException(
                        "maxPercentOver (" + request.maxPercentOver() + ") must be greater than minPercentOver ("
                                + request.minPercentOver() + ") for tier " + request.tier());
            }
        }
    }

    private Policy findPolicy(UUID policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy not found with id: " + policyId));
    }
}
