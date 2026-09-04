package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import org.springframework.stereotype.Component;

@Component
public class PolicyRuleMapper {

    public PolicyRule toEntity(PolicyRuleRequest request) {
        return PolicyRule.builder()
                .policyName(request.policyName())
                .ruleType(request.ruleType())
                .ruleValue(request.ruleValue())
                .severity(resolveSeverity(request))
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .status(request.status())
                .build();
    }

    public void updateEntity(PolicyRule entity, PolicyRuleRequest request) {
        entity.setPolicyName(request.policyName());
        entity.setRuleType(request.ruleType());
        entity.setRuleValue(request.ruleValue());
        entity.setSeverity(resolveSeverity(request));
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
        entity.setStatus(request.status());
    }

    /** DUPLICATE_EXPENSE is the one rule type prone to false positives, so it defaults to the lower INFO tier when the admin doesn't specify a severity; every other type defaults to WARN. */
    private PolicySeverity resolveSeverity(PolicyRuleRequest request) {
        if (request.severity() != null) {
            return request.severity();
        }
        return request.ruleType() == PolicyRuleType.DUPLICATE_EXPENSE ? PolicySeverity.INFO : PolicySeverity.WARN;
    }

    public PolicyRuleResponse toResponse(PolicyRule entity) {
        return new PolicyRuleResponse(
                entity.getPolicyId(),
                entity.getCategory() != null ? entity.getCategory().getCategoryId() : null,
                entity.getCategory() != null ? entity.getCategory().getCategoryName() : null,
                entity.getPolicyName(),
                entity.getRuleType(),
                entity.getRuleValue(),
                entity.getSeverity(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
