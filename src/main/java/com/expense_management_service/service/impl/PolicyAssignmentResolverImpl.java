package com.expense_management_service.service.impl;

import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyAssignment;
import com.expense_management_service.entity.PolicyGroupMember;
import com.expense_management_service.enums.PolicyAssignmentType;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyGroupMemberRepository;
import com.expense_management_service.service.PolicyAssignmentResolver;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PolicyAssignmentResolverImpl implements PolicyAssignmentResolver {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PolicyAssignmentRepository policyAssignmentRepository;
    private final PolicyGroupMemberRepository policyGroupMemberRepository;

    @Override
    public Policy resolve(String employeeId) {
        if (employeeId != null) {
            Optional<PolicyAssignment> individual = policyAssignmentRepository
                    .findFirstByEmployeeIdAndAssignmentTypeAndStatus(employeeId, PolicyAssignmentType.INDIVIDUAL, STATUS_ACTIVE);
            if (individual.isPresent()) {
                return individual.get().getPolicy();
            }

            Optional<PolicyAssignment> groupAssignment = policyGroupMemberRepository.findByEmployeeId(employeeId)
                    .flatMap(membership -> policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(
                            membership.getGroup().getGroupId(), PolicyAssignmentType.GROUP, STATUS_ACTIVE));
            if (groupAssignment.isPresent()) {
                return groupAssignment.get().getPolicy();
            }
        }

        return policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "No active DEFAULT policy assignment exists - the Phase 1 seed migration should guarantee exactly one"))
                .getPolicy();
    }
}
