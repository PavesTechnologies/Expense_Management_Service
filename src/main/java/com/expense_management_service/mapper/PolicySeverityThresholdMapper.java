package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.PolicySeverityThresholdResponse;
import com.expense_management_service.entity.PolicySeverityThreshold;
import org.springframework.stereotype.Component;

@Component
public class PolicySeverityThresholdMapper {

    public PolicySeverityThresholdResponse toResponse(PolicySeverityThreshold entity) {
        return new PolicySeverityThresholdResponse(
                entity.getThresholdId(),
                entity.getPolicy() != null ? entity.getPolicy().getPolicyId() : null,
                entity.getTier(),
                entity.getMinPercentOver(),
                entity.getMaxPercentOver(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
