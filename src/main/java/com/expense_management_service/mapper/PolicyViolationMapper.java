package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.entity.PolicyViolation;
import org.springframework.stereotype.Component;

@Component
public class PolicyViolationMapper {

    public PolicyWarningResponse toResponse(PolicyViolation entity) {
        return new PolicyWarningResponse(
                entity.getViolationId(),
                entity.getRuleType(),
                entity.getSeverity(),
                entity.getEnforcementType(),
                entity.getMessage(),
                entity.getLimitValue(),
                entity.getActualValue(),
                entity.getOveragePercent(),
                entity.getSeverityTier(),
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getJustification(),
                entity.getJustifiedAt(),
                entity.getPolicyVersionNumber()
        );
    }
}
