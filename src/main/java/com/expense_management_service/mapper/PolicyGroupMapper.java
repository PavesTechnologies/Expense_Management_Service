package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.PolicyGroupRequest;
import com.expense_management_service.dto.response.PolicyGroupMemberResponse;
import com.expense_management_service.dto.response.PolicyGroupResponse;
import com.expense_management_service.entity.PolicyGroup;
import com.expense_management_service.entity.PolicyGroupMember;
import org.springframework.stereotype.Component;

@Component
public class PolicyGroupMapper {

    public PolicyGroup toEntity(PolicyGroupRequest request) {
        return PolicyGroup.builder()
                .groupName(request.groupName())
                .description(request.description())
                .status(request.status())
                .build();
    }

    public void updateEntity(PolicyGroup entity, PolicyGroupRequest request) {
        entity.setGroupName(request.groupName());
        entity.setDescription(request.description());
        entity.setStatus(request.status());
    }

    public PolicyGroupResponse toResponse(PolicyGroup entity, int memberCount) {
        return new PolicyGroupResponse(
                entity.getGroupId(),
                entity.getGroupName(),
                entity.getDescription(),
                entity.getStatus(),
                memberCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public PolicyGroupMemberResponse toMemberResponse(PolicyGroupMember entity) {
        return new PolicyGroupMemberResponse(
                entity.getMemberId(),
                entity.getGroup() != null ? entity.getGroup().getGroupId() : null,
                entity.getEmployeeId(),
                entity.getCreatedAt()
        );
    }
}
