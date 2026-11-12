package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyRequest;
import com.expense_management_service.dto.response.PolicyResponse;
import com.expense_management_service.dto.response.PolicyVersionResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.mapper.PolicyMapper;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.service.PolicyService;
import com.expense_management_service.service.PolicyVersionService;
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
public class PolicyServiceImpl implements PolicyService {

    private final PolicyRepository policyRepository;
    private final PolicyRuleRepository policyRuleRepository;
    private final PolicyAssignmentRepository policyAssignmentRepository;
    private final PolicyVersionService policyVersionService;
    private final PolicyMapper policyMapper;

    @Override
    public PolicyResponse create(PolicyRequest request) {
        assertNameNotTaken(request.policyName());

        Policy saved = policyRepository.save(policyMapper.toEntity(request));
        log.info("Created policy {} ({})", saved.getPolicyId(), saved.getPolicyName());
        return policyMapper.toResponse(saved, policyVersionService.getCurrentVersion(saved.getPolicyId()));
    }

    @Override
    public PolicyResponse update(UUID policyId, PolicyRequest request) {
        Policy entity = findEntity(policyId);
        if (!entity.getPolicyName().equalsIgnoreCase(request.policyName())) {
            assertNameNotTaken(request.policyName());
        }

        policyMapper.updateEntity(entity, request);
        Policy saved = policyRepository.save(entity);
        log.info("Updated policy {}", policyId);
        return policyMapper.toResponse(saved, policyVersionService.getCurrentVersion(policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyResponse getById(UUID policyId) {
        Policy entity = findEntity(policyId);
        return policyMapper.toResponse(entity, policyVersionService.getCurrentVersion(policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyResponse> getAll() {
        return policyRepository.findAll().stream()
                .map(p -> policyMapper.toResponse(p, policyVersionService.getCurrentVersion(p.getPolicyId())))
                .toList();
    }

    @Override
    public void delete(UUID policyId) {
        Policy entity = findEntity(policyId);
        if (policyRuleRepository.existsByPolicy_PolicyId(policyId)) {
            throw new ResourceInUseException(
                    "Policy " + entity.getPolicyName() + " still has rules attached - remove them before deleting the policy");
        }
        if (policyAssignmentRepository.existsByPolicy_PolicyId(policyId)) {
            throw new ResourceInUseException(
                    "Policy " + entity.getPolicyName() + " is still assigned (individually, to a group, or as the system Default) - "
                            + "repoint or remove that assignment before deleting the policy");
        }
        policyRepository.delete(entity);
        log.info("Deleted policy {}", policyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyVersionResponse> getVersionHistory(UUID policyId) {
        findEntity(policyId);
        return policyVersionService.getVersionHistory(policyId);
    }

    private void assertNameNotTaken(String policyName) {
        policyRepository.findByPolicyName(policyName).ifPresent(existing -> {
            throw new DuplicateResourceException("A policy named '" + policyName + "' already exists");
        });
    }

    private Policy findEntity(UUID policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy not found with id: " + policyId));
    }
}
