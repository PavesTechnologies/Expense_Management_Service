package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.PolicyAssignmentResponse;
import com.expense_management_service.entity.PolicyAssignment;
import org.springframework.stereotype.Component;

@Component
public class PolicyAssignmentMapper {

    public PolicyAssignmentResponse toResponse(PolicyAssignment entity) {
        return new PolicyAssignmentResponse(
                entity.getAssignmentId(),
                entity.getAssignmentType(),
                entity.getEmployeeId(),
                entity.getGroup() != null ? entity.getGroup().getGroupId() : null,
                entity.getGroup() != null ? entity.getGroup().getGroupName() : null,
                entity.getPolicy() != null ? entity.getPolicy().getPolicyId() : null,
                entity.getPolicy() != null ? entity.getPolicy().getPolicyName() : null,
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
