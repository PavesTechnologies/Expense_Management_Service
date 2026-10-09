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
class PolicyAssignmentServiceImplTest {

    @Mock
    private PolicyAssignmentRepository policyAssignmentRepository;
    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private PolicyGroupRepository policyGroupRepository;

    private PolicyAssignmentServiceImpl service;

    private UUID policyId;
    private Policy policy;

    @BeforeEach
    void setUp() {
        service = new PolicyAssignmentServiceImpl(policyAssignmentRepository, policyRepository, policyGroupRepository, new PolicyAssignmentMapper());
        policyId = UUID.randomUUID();
        policy = Policy.builder().policyId(policyId).policyName("Field Sales Policy").status("ACTIVE").build();
    }

    @Test
    void create_savesIndividualAssignment_whenEmployeeHasNoActiveIndividualAssignment() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus("5100014", PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.empty());
        when(policyAssignmentRepository.save(any(PolicyAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyAssignmentResponse response = service.create(new PolicyAssignmentRequest(PolicyAssignmentType.INDIVIDUAL, "5100014", null, policyId, "ACTIVE"));

        assertThat(response.assignmentType()).isEqualTo(PolicyAssignmentType.INDIVIDUAL);
        assertThat(response.employeeId()).isEqualTo("5100014");
        assertThat(response.policyId()).isEqualTo(policyId);
    }

    @Test
    void create_throwsDuplicateResource_whenEmployeeAlreadyHasActiveIndividualAssignment() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyAssignmentRepository.findFirstByEmployeeIdAndAssignmentTypeAndStatus("5100014", PolicyAssignmentType.INDIVIDUAL, "ACTIVE"))
                .thenReturn(Optional.of(PolicyAssignment.builder().assignmentId(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.create(new PolicyAssignmentRequest(PolicyAssignmentType.INDIVIDUAL, "5100014", null, policyId, "ACTIVE")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(policyAssignmentRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgument_whenIndividualAssignmentMissingEmployeeId() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.create(new PolicyAssignmentRequest(PolicyAssignmentType.INDIVIDUAL, null, null, policyId, "ACTIVE")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_savesGroupAssignment_whenGroupHasNoActiveAssignment() {
        UUID groupId = UUID.randomUUID();
        PolicyGroup group = PolicyGroup.builder().groupId(groupId).groupName("Field Sales").status("ACTIVE").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, "ACTIVE"))
                .thenReturn(Optional.empty());
        when(policyAssignmentRepository.save(any(PolicyAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyAssignmentResponse response = service.create(new PolicyAssignmentRequest(PolicyAssignmentType.GROUP, null, groupId, policyId, "ACTIVE"));

        assertThat(response.assignmentType()).isEqualTo(PolicyAssignmentType.GROUP);
        assertThat(response.groupId()).isEqualTo(groupId);
    }

    @Test
    void create_throwsDuplicateResource_whenGroupAlreadyHasActiveAssignment() {
        UUID groupId = UUID.randomUUID();
        PolicyGroup group = PolicyGroup.builder().groupId(groupId).groupName("Field Sales").status("ACTIVE").build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, "ACTIVE"))
                .thenReturn(Optional.of(PolicyAssignment.builder().assignmentId(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.create(new PolicyAssignmentRequest(PolicyAssignmentType.GROUP, null, groupId, policyId, "ACTIVE")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(policyAssignmentRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgument_whenAssignmentTypeIsDefault() {
        assertThatThrownBy(() -> service.create(new PolicyAssignmentRequest(PolicyAssignmentType.DEFAULT, null, null, policyId, "ACTIVE")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(policyAssignmentRepository, never()).save(any());
    }

    @Test
    void delete_throwsIllegalArgument_whenAssignmentIsDefault() {
        UUID assignmentId = UUID.randomUUID();
        PolicyAssignment defaultAssignment = PolicyAssignment.builder().assignmentId(assignmentId)
                .assignmentType(PolicyAssignmentType.DEFAULT).policy(policy).status("ACTIVE").build();
        when(policyAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(defaultAssignment));

        assertThatThrownBy(() -> service.delete(assignmentId)).isInstanceOf(IllegalArgumentException.class);

        verify(policyAssignmentRepository, never()).delete(any());
    }

    @Test
    void delete_removesAssignment_whenIndividualOrGroup() {
        UUID assignmentId = UUID.randomUUID();
        PolicyAssignment individual = PolicyAssignment.builder().assignmentId(assignmentId)
                .assignmentType(PolicyAssignmentType.INDIVIDUAL).employeeId("5100014").policy(policy).status("ACTIVE").build();
        when(policyAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(individual));

        service.delete(assignmentId);

        verify(policyAssignmentRepository).delete(individual);
    }

    @Test
    void updateDefaultPolicy_repointsExistingDefaultAssignment() {
        UUID newPolicyId = UUID.randomUUID();
        Policy newPolicy = Policy.builder().policyId(newPolicyId).policyName("New Default").status("ACTIVE").build();
        PolicyAssignment defaultAssignment = PolicyAssignment.builder().assignmentId(UUID.randomUUID())
                .assignmentType(PolicyAssignmentType.DEFAULT).policy(policy).status("ACTIVE").build();
        when(policyRepository.findById(newPolicyId)).thenReturn(Optional.of(newPolicy));
        when(policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE"))
                .thenReturn(Optional.of(defaultAssignment));
        when(policyAssignmentRepository.save(any(PolicyAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyAssignmentResponse response = service.updateDefaultPolicy(newPolicyId);

        assertThat(response.policyId()).isEqualTo(newPolicyId);
    }

    @Test
    void updateDefaultPolicy_throwsIllegalState_whenNoDefaultAssignmentExists() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDefaultPolicy(policyId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void create_throwsResourceNotFound_whenPolicyMissing() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new PolicyAssignmentRequest(PolicyAssignmentType.INDIVIDUAL, "5100014", null, policyId, "ACTIVE")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
