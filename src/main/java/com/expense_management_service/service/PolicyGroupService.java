package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicyGroupMemberRequest;
import com.expense_management_service.dto.request.PolicyGroupRequest;
import com.expense_management_service.dto.response.PolicyGroupMemberResponse;
import com.expense_management_service.dto.response.PolicyGroupResponse;

import java.util.List;
import java.util.UUID;

public interface PolicyGroupService {

    PolicyGroupResponse create(PolicyGroupRequest request);

    PolicyGroupResponse update(UUID groupId, PolicyGroupRequest request);

    PolicyGroupResponse getById(UUID groupId);

    List<PolicyGroupResponse> getAll();

    void delete(UUID groupId);

    PolicyGroupMemberResponse addMember(UUID groupId, PolicyGroupMemberRequest request);

    void removeMember(UUID groupId, String employeeId);

    List<PolicyGroupMemberResponse> getMembers(UUID groupId);
}
