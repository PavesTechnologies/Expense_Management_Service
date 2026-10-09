package com.expense_management_service.service.impl;

import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyAssignment;
import com.expense_management_service.entity.PolicyGroup;
import com.expense_management_service.entity.PolicyGroupMember;
import com.expense_management_service.enums.PolicyAssignmentType;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyGroupMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers the Individual &gt; Group &gt; Default precedence this resolver is the single source of
 * truth for - every branch, plus the two "should never happen" invariants (no employeeId, no
 * DEFAULT assignment at all) that the Phase 1 seed migration is supposed to make impossible.
 */
@ExtendWith(MockitoExtension.class)
class PolicyAssignmentResolverImplTest {

    @Mock
    private PolicyAssignmentRepository policyAssignmentRepository;
    @Mock
    private PolicyGroupMemberRepository policyGroupMemberRepository;

    private PolicyAssignmentResolverImpl resolver;

    private static final String EMPLOYEE_ID = "5100014";

    @BeforeEach
    void setUp() {
        resolver = new PolicyAssignmentResolverImpl(policyAssignmentRepository, policyGroupMemberRepository);
    }

    private Policy policy(String name) {
        return Policy.builder().policyId(UUID.randomUUID()).policyName(name).status("ACTIVE").build();
    }

    @Test
    void resolve_returnsIndividualPolicy_whenIndividualAssignmentExists() {
        Policy individualPolicy = policy("Executive Policy");
        PolicyAssignment individual = PolicyAssignment.builder().assignmentId(UUID.randomUUID())
                .assignmentType(PolicyAssignmentType.INDIVIDUAL).employeeId(EMPLOYEE_ID).policy(individualPolicy).status("ACTIVE").build();
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus(EMPLOYEE_ID, PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.of(individual));

        Policy resolved = resolver.resolve(EMPLOYEE_ID);

        assertThat(resolved).isEqualTo(individualPolicy);
    }

    @Test
    void resolve_returnsGroupPolicy_whenNoIndividualButGroupAssignmentExists() {
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus(EMPLOYEE_ID, PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.empty());

        UUID groupId = UUID.randomUUID();
        PolicyGroup group = PolicyGroup.builder().groupId(groupId).groupName("Field Sales").status("ACTIVE").build();
        PolicyGroupMember membership = PolicyGroupMember.builder().memberId(UUID.randomUUID()).group(group).employeeId(EMPLOYEE_ID).build();
        when(policyGroupMemberRepository.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(membership));

        Policy groupPolicy = policy("Field Sales Policy");
        PolicyAssignment groupAssignment = PolicyAssignment.builder().assignmentId(UUID.randomUUID())
                .assignmentType(PolicyAssignmentType.GROUP).group(group).policy(groupPolicy).status("ACTIVE").build();
        when(policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, "ACTIVE"))
                .thenReturn(Optional.of(groupAssignment));

        Policy resolved = resolver.resolve(EMPLOYEE_ID);

        assertThat(resolved).isEqualTo(groupPolicy);
    }

    @Test
    void resolve_returnsDefaultPolicy_whenNeitherIndividualNorGroupExists() {
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus(EMPLOYEE_ID, PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.empty());
        when(policyGroupMemberRepository.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());

        Policy defaultPolicy = policy("Default Policy");
        PolicyAssignment defaultAssignment = PolicyAssignment.builder().assignmentId(UUID.randomUUID())
                .assignmentType(PolicyAssignmentType.DEFAULT).policy(defaultPolicy).status("ACTIVE").build();
        when(policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE"))
                .thenReturn(Optional.of(defaultAssignment));

        Policy resolved = resolver.resolve(EMPLOYEE_ID);

        assertThat(resolved).isEqualTo(defaultPolicy);
    }

    @Test
    void resolve_returnsDefaultPolicy_whenEmployeeInGroupButGroupHasNoAssignment() {
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus(EMPLOYEE_ID, PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.empty());

        UUID groupId = UUID.randomUUID();
        PolicyGroup group = PolicyGroup.builder().groupId(groupId).groupName("Unassigned Group").status("ACTIVE").build();
        PolicyGroupMember membership = PolicyGroupMember.builder().memberId(UUID.randomUUID()).group(group).employeeId(EMPLOYEE_ID).build();
        when(policyGroupMemberRepository.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.of(membership));
        when(policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, "ACTIVE"))
                .thenReturn(Optional.empty());

        Policy defaultPolicy = policy("Default Policy");
        PolicyAssignment defaultAssignment = PolicyAssignment.builder().assignmentId(UUID.randomUUID())
                .assignmentType(PolicyAssignmentType.DEFAULT).policy(defaultPolicy).status("ACTIVE").build();
        when(policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE"))
                .thenReturn(Optional.of(defaultAssignment));

        Policy resolved = resolver.resolve(EMPLOYEE_ID);

        assertThat(resolved).isEqualTo(defaultPolicy);
    }

    @Test
    void resolve_skipsStraightToDefault_whenEmployeeIdIsNull() {
        Policy defaultPolicy = policy("Default Policy");
        PolicyAssignment defaultAssignment = PolicyAssignment.builder().assignmentId(UUID.randomUUID())
                .assignmentType(PolicyAssignmentType.DEFAULT).policy(defaultPolicy).status("ACTIVE").build();
        when(policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE"))
                .thenReturn(Optional.of(defaultAssignment));

        Policy resolved = resolver.resolve(null);

        assertThat(resolved).isEqualTo(defaultPolicy);
    }

    @Test
    void resolve_throwsIllegalState_whenNoDefaultAssignmentExists() {
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus(EMPLOYEE_ID, PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.empty());
        when(policyGroupMemberRepository.findByEmployeeId(EMPLOYEE_ID)).thenReturn(Optional.empty());
        when(policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(EMPLOYEE_ID)).isInstanceOf(IllegalStateException.class);
    }
}
