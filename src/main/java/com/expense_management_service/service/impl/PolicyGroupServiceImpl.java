package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyGroupMemberRequest;
import com.expense_management_service.dto.request.PolicyGroupRequest;
import com.expense_management_service.dto.response.PolicyGroupMemberResponse;
import com.expense_management_service.dto.response.PolicyGroupResponse;
import com.expense_management_service.entity.PolicyGroup;
import com.expense_management_service.entity.PolicyGroupMember;
import com.expense_management_service.enums.PolicyAssignmentType;
import com.expense_management_service.mapper.PolicyGroupMapper;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyGroupMemberRepository;
import com.expense_management_service.repository.PolicyGroupRepository;
import com.expense_management_service.service.PolicyGroupService;
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
public class PolicyGroupServiceImpl implements PolicyGroupService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PolicyGroupRepository policyGroupRepository;
    private final PolicyGroupMemberRepository policyGroupMemberRepository;
    private final PolicyAssignmentRepository policyAssignmentRepository;
    private final PolicyGroupMapper policyGroupMapper;

    @Override
    public PolicyGroupResponse create(PolicyGroupRequest request) {
        assertNameNotTaken(request.groupName());

        PolicyGroup entity = policyGroupMapper.toEntity(request);
        PolicyGroup saved = policyGroupRepository.save(entity);
        log.info("Created policy group {} ({})", saved.getGroupId(), saved.getGroupName());
        return policyGroupMapper.toResponse(saved, 0);
    }

    @Override
    public PolicyGroupResponse update(UUID groupId, PolicyGroupRequest request) {
        PolicyGroup entity = findEntity(groupId);
        if (!entity.getGroupName().equalsIgnoreCase(request.groupName())) {
            assertNameNotTaken(request.groupName());
        }

        policyGroupMapper.updateEntity(entity, request);
        PolicyGroup saved = policyGroupRepository.save(entity);
        log.info("Updated policy group {}", groupId);
        return policyGroupMapper.toResponse(saved, (int) policyGroupMemberRepository.countByGroup_GroupId(groupId));
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyGroupResponse getById(UUID groupId) {
        PolicyGroup entity = findEntity(groupId);
        return policyGroupMapper.toResponse(entity, (int) policyGroupMemberRepository.countByGroup_GroupId(groupId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyGroupResponse> getAll() {
        return policyGroupRepository.findAll().stream()
                .map(g -> policyGroupMapper.toResponse(g, (int) policyGroupMemberRepository.countByGroup_GroupId(g.getGroupId())))
                .toList();
    }

    @Override
    public void delete(UUID groupId) {
        PolicyGroup entity = findEntity(groupId);
        if (policyGroupMemberRepository.countByGroup_GroupId(groupId) > 0) {
            throw new ResourceInUseException(
                    "Policy group " + entity.getGroupName() + " still has members - remove them before deleting the group");
        }
        if (policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, STATUS_ACTIVE).isPresent()) {
            throw new ResourceInUseException(
                    "Policy group " + entity.getGroupName() + " still has an active policy assignment - remove it before deleting the group");
        }
        policyGroupRepository.delete(entity);
        log.info("Deleted policy group {}", groupId);
    }

    @Override
    public PolicyGroupMemberResponse addMember(UUID groupId, PolicyGroupMemberRequest request) {
        PolicyGroup group = findEntity(groupId);
        policyGroupMemberRepository.findByEmployeeId(request.employeeId()).ifPresent(existing -> {
            throw new DuplicateResourceException("Employee " + request.employeeId()
                    + " already belongs to policy group " + existing.getGroup().getGroupName()
                    + " - an employee belongs to at most one policy-determining group at a time");
        });

        PolicyGroupMember member = PolicyGroupMember.builder().group(group).employeeId(request.employeeId()).build();
        PolicyGroupMember saved = policyGroupMemberRepository.save(member);
        log.info("Added employee {} to policy group {}", request.employeeId(), groupId);
        return policyGroupMapper.toMemberResponse(saved);
    }

    @Override
    public void removeMember(UUID groupId, String employeeId) {
        PolicyGroupMember member = policyGroupMemberRepository.findByGroup_GroupIdAndEmployeeId(groupId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee " + employeeId + " is not a member of policy group " + groupId));
        policyGroupMemberRepository.delete(member);
        log.info("Removed employee {} from policy group {}", employeeId, groupId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyGroupMemberResponse> getMembers(UUID groupId) {
        findEntity(groupId);
        return policyGroupMemberRepository.findByGroup_GroupId(groupId).stream()
                .map(policyGroupMapper::toMemberResponse)
                .toList();
    }

    private void assertNameNotTaken(String groupName) {
        policyGroupRepository.findByGroupName(groupName).ifPresent(existing -> {
            throw new DuplicateResourceException("A policy group named '" + groupName + "' already exists");
        });
    }

    private PolicyGroup findEntity(UUID groupId) {
        return policyGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyGroup not found with id: " + groupId));
    }
}
