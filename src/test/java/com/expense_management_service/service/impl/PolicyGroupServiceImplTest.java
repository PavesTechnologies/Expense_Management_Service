package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyGroupMemberRequest;
import com.expense_management_service.dto.request.PolicyGroupRequest;
import com.expense_management_service.dto.response.PolicyGroupMemberResponse;
import com.expense_management_service.entity.PolicyAssignment;
import com.expense_management_service.entity.PolicyGroup;
import com.expense_management_service.entity.PolicyGroupMember;
import com.expense_management_service.enums.PolicyAssignmentType;
import com.expense_management_service.mapper.PolicyGroupMapper;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyGroupMemberRepository;
import com.expense_management_service.repository.PolicyGroupRepository;
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
class PolicyGroupServiceImplTest {

    @Mock
    private PolicyGroupRepository policyGroupRepository;
    @Mock
    private PolicyGroupMemberRepository policyGroupMemberRepository;
    @Mock
    private PolicyAssignmentRepository policyAssignmentRepository;

    private PolicyGroupServiceImpl policyGroupService;

    private UUID groupId;
    private PolicyGroup group;

    @BeforeEach
    void setUp() {
        policyGroupService = new PolicyGroupServiceImpl(policyGroupRepository, policyGroupMemberRepository, policyAssignmentRepository, new PolicyGroupMapper());
        groupId = UUID.randomUUID();
        group = PolicyGroup.builder().groupId(groupId).groupName("Field Sales").status("ACTIVE").build();
    }

    @Test
    void create_savesGroup_whenNameNotTaken() {
        when(policyGroupRepository.findByGroupName("Field Sales")).thenReturn(Optional.empty());
        when(policyGroupRepository.save(any(PolicyGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = policyGroupService.create(new PolicyGroupRequest("Field Sales", "desc", "ACTIVE"));

        assertThat(response.groupName()).isEqualTo("Field Sales");
        assertThat(response.memberCount()).isZero();
    }

    @Test
    void create_throwsDuplicateResource_whenNameAlreadyTaken() {
        when(policyGroupRepository.findByGroupName("Field Sales")).thenReturn(Optional.of(group));

        assertThatThrownBy(() -> policyGroupService.create(new PolicyGroupRequest("Field Sales", null, "ACTIVE")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(policyGroupRepository, never()).save(any());
    }

    @Test
    void delete_throwsResourceInUse_whenGroupStillHasMembers() {
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyGroupMemberRepository.countByGroup_GroupId(groupId)).thenReturn(1L);

        assertThatThrownBy(() -> policyGroupService.delete(groupId)).isInstanceOf(ResourceInUseException.class);

        verify(policyGroupRepository, never()).delete(any());
    }

    @Test
    void delete_throwsResourceInUse_whenGroupStillHasActiveAssignment() {
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyGroupMemberRepository.countByGroup_GroupId(groupId)).thenReturn(0L);
        when(policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, "ACTIVE"))
                .thenReturn(Optional.of(PolicyAssignment.builder().assignmentId(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> policyGroupService.delete(groupId)).isInstanceOf(ResourceInUseException.class);

        verify(policyGroupRepository, never()).delete(any());
    }

    @Test
    void delete_succeeds_whenGroupIsEmptyAndUnassigned() {
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyGroupMemberRepository.countByGroup_GroupId(groupId)).thenReturn(0L);
        when(policyAssignmentRepository.findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(groupId, PolicyAssignmentType.GROUP, "ACTIVE"))
                .thenReturn(Optional.empty());

        policyGroupService.delete(groupId);

        verify(policyGroupRepository).delete(group);
    }

    @Test
    void addMember_savesMembership_whenEmployeeNotAlreadyInAnyGroup() {
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyGroupMemberRepository.findByEmployeeId("5100014")).thenReturn(Optional.empty());
        when(policyGroupMemberRepository.save(any(PolicyGroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyGroupMemberResponse response = policyGroupService.addMember(groupId, new PolicyGroupMemberRequest("5100014"));

        assertThat(response.employeeId()).isEqualTo("5100014");
        assertThat(response.groupId()).isEqualTo(groupId);
    }

    @Test
    void addMember_throwsDuplicateResource_whenEmployeeAlreadyInAnotherGroup() {
        PolicyGroup otherGroup = PolicyGroup.builder().groupId(UUID.randomUUID()).groupName("Development").status("ACTIVE").build();
        PolicyGroupMember existingMembership = PolicyGroupMember.builder().memberId(UUID.randomUUID()).group(otherGroup).employeeId("5100014").build();
        when(policyGroupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(policyGroupMemberRepository.findByEmployeeId("5100014")).thenReturn(Optional.of(existingMembership));

        assertThatThrownBy(() -> policyGroupService.addMember(groupId, new PolicyGroupMemberRequest("5100014")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Development");

        verify(policyGroupMemberRepository, never()).save(any());
    }

    @Test
    void removeMember_deletesMembership_whenFound() {
        PolicyGroupMember membership = PolicyGroupMember.builder().memberId(UUID.randomUUID()).group(group).employeeId("5100014").build();
        when(policyGroupMemberRepository.findByGroup_GroupIdAndEmployeeId(groupId, "5100014")).thenReturn(Optional.of(membership));

        policyGroupService.removeMember(groupId, "5100014");

        verify(policyGroupMemberRepository).delete(membership);
    }

    @Test
    void removeMember_throwsResourceNotFound_whenNotAMember() {
        when(policyGroupMemberRepository.findByGroup_GroupIdAndEmployeeId(groupId, "5100014")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyGroupService.removeMember(groupId, "5100014"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
