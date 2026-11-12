package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyGroupMemberRepository extends JpaRepository<PolicyGroupMember, UUID> {

    /** At most one row per employee - see {@link PolicyGroupMember}'s uk_policy_group_member_employee constraint. */
    Optional<PolicyGroupMember> findByEmployeeId(String employeeId);

    List<PolicyGroupMember> findByGroup_GroupId(UUID groupId);

    Optional<PolicyGroupMember> findByGroup_GroupIdAndEmployeeId(UUID groupId, String employeeId);

    long countByGroup_GroupId(UUID groupId);
}
