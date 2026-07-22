package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.PolicyRule;
import org.springframework.stereotype.Component;

@Component
public class PolicyRuleMapper {

    public PolicyRule toEntity(PolicyRuleRequest request) {
        return PolicyRule.builder()
                .policyName(request.policyName())
                .ruleType(request.ruleType())
                .ruleValue(request.ruleValue())
                .action(request.action())
                .severity(request.severity())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .status(request.status())
                .build();
    }

    public void updateEntity(PolicyRule entity, PolicyRuleRequest request) {
        entity.setPolicyName(request.policyName());
        entity.setRuleType(request.ruleType());
        entity.setRuleValue(request.ruleValue());
        entity.setAction(request.action());
        entity.setSeverity(request.severity());
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
        entity.setStatus(request.status());
    }

    public PolicyRuleResponse toResponse(PolicyRule entity) {
        return new PolicyRuleResponse(
                entity.getPolicyId(),
                entity.getCategory() != null ? entity.getCategory().getCategoryId() : null,
                entity.getCategory() != null ? entity.getCategory().getCategoryName() : null,
                entity.getPolicyName(),
                entity.getRuleType(),
                entity.getRuleValue(),
                entity.getAction(),
                entity.getSeverity(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getStatus()
        );
    }
}
