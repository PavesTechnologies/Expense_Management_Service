package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyAssignmentRequest;
import com.expense_management_service.dto.response.PolicyAssignmentResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyAssignment;
import com.expense_management_service.entity.PolicyGroup;
import com.expense_management_service.enums.PolicyAssignmentType;
import com.expense_management_service.mapper.PolicyAssignmentMapper;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyGroupRepository;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.service.PolicyAssignmentService;
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
public class PolicyAssignmentServiceImpl implements PolicyAssignmentService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PolicyAssignmentRepository policyAssignmentRepository;
    private final PolicyRepository policyRepository;
    private final PolicyGroupRepository policyGroupRepository;
    private final PolicyAssignmentMapper policyAssignmentMapper;

    @Override
    public PolicyAssignmentResponse create(PolicyAssignmentRequest request) {
        if (request.assignmentType() == PolicyAssignmentType.DEFAULT) {
            throw new IllegalArgumentException(
                    "The system-wide DEFAULT assignment already exists and cannot be recreated - use updateDefaultPolicy instead");
        }

        Policy policy = findPolicy(request.policyId());

        PolicyAssignment.PolicyAssignmentBuilder builder = PolicyAssignment.builder()
                .assignmentType(request.assignmentType())
                .policy(policy)
                .status(request.status() != null ? request.status() : STATUS_ACTIVE);

        if (request.assignmentType() == PolicyAssignmentType.INDIVIDUAL) {
            if (request.employeeId() == null || request.employeeId().isBlank()) {
                throw new IllegalArgumentException("employeeId is required for an INDIVIDUAL assignment");
            }
            policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus(request.employeeId(), PolicyAssignmentType.INDIVIDUAL, STATUS_ACTIVE)
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException(
                                "Employee " + request.employeeId() + " already has an active individual policy assignment");
                    });
            builder.employeeId(request.employeeId());
        } else {
            if (request.groupId() == null) {
                throw new IllegalArgumentException("groupId is required for a GROUP assignment");
            }
            PolicyGroup group = policyGroupRepository.findById(request.groupId())
                    .orElseThrow(() -> new ResourceNotFoundException("PolicyGroup not found with id: " + request.groupId()));
            policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(request.groupId(), PolicyAssignmentType.GROUP, STATUS_ACTIVE)
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException(
                                "Policy group " + group.getGroupName() + " already has an active policy assignment");
                    });
            builder.group(group);
        }

        PolicyAssignment saved = policyAssignmentRepository.save(builder.build());
        log.info("Created {} policy assignment {} -> policy {}", saved.getAssignmentType(), saved.getAssignmentId(), policy.getPolicyId());
        return policyAssignmentMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyAssignmentResponse getById(UUID assignmentId) {
        return policyAssignmentMapper.toResponse(findEntity(assignmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyAssignmentResponse> getAll() {
        return policyAssignmentRepository.findAll().stream().map(policyAssignmentMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID assignmentId) {
        PolicyAssignment entity = findEntity(assignmentId);
        if (entity.getAssignmentType() == PolicyAssignmentType.DEFAULT) {
            throw new IllegalArgumentException("The system-wide DEFAULT assignment cannot be deleted");
        }
        policyAssignmentRepository.delete(entity);
        log.info("Deleted {} policy assignment {}", entity.getAssignmentType(), assignmentId);
    }

    @Override
    public PolicyAssignmentResponse updateDefaultPolicy(UUID newPolicyId) {
        Policy policy = findPolicy(newPolicyId);
        PolicyAssignment defaultAssignment = policyAssignmentRepository
                .findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "No active DEFAULT policy assignment exists - the Phase 1 seed migration should guarantee exactly one"));
        defaultAssignment.setPolicy(policy);
        PolicyAssignment saved = policyAssignmentRepository.save(defaultAssignment);
        log.info("Repointed the system-wide DEFAULT policy assignment to policy {}", policy.getPolicyId());
        return policyAssignmentMapper.toResponse(saved);
    }

    private Policy findPolicy(UUID policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy not found with id: " + policyId));
    }

    private PolicyAssignment findEntity(UUID assignmentId) {
        return policyAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyAssignment not found with id: " + assignmentId));
    }
}
