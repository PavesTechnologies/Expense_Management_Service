package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.PolicyRequest;
import com.expense_management_service.dto.response.PolicyResponse;
import com.expense_management_service.entity.Policy;
import org.springframework.stereotype.Component;

@Component
public class PolicyMapper {

    public Policy toEntity(PolicyRequest request) {
        return Policy.builder()
                .policyName(request.policyName())
                .description(request.description())
                .status(request.status())
                .build();
    }

    public void updateEntity(Policy entity, PolicyRequest request) {
        entity.setPolicyName(request.policyName());
        entity.setDescription(request.description());
        entity.setStatus(request.status());
    }

    public PolicyResponse toResponse(Policy entity, int currentVersion) {
        return new PolicyResponse(
                entity.getPolicyId(),
                entity.getPolicyName(),
                entity.getDescription(),
                entity.getStatus(),
                currentVersion,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
