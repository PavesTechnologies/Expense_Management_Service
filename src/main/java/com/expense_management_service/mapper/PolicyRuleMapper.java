package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleLimitResponse;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyRuleLimit;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolicyRuleMapper {

    public PolicyRule toEntity(PolicyRuleRequest request) {
        return PolicyRule.builder()
                .policyName(request.policyName())
                .ruleType(request.ruleType())
                .ruleValue(request.ruleValue())
                .severity(resolveSeverity(request))
                .enforcementType(resolveEnforcementType(request))
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
        entity.setEnforcementType(resolveEnforcementType(request));
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

    /** Block is never a silent default - an Admin who doesn't specify enforcementType always gets WARN, regardless of rule type. */
    private PolicyEnforcementType resolveEnforcementType(PolicyRuleRequest request) {
        return request.enforcementType() != null ? request.enforcementType() : PolicyEnforcementType.WARN;
    }

    public PolicyRuleResponse toResponse(PolicyRule entity, List<PolicyRuleLimit> limits) {
        return new PolicyRuleResponse(
                entity.getPolicyId(),
                entity.getPolicy() != null ? entity.getPolicy().getPolicyId() : null,
                entity.getCategory() != null ? entity.getCategory().getCategoryId() : null,
                entity.getCategory() != null ? entity.getCategory().getCategoryName() : null,
                entity.getPolicyName(),
                entity.getRuleType(),
                entity.getRuleValue(),
                entity.getSeverity(),
                entity.getEnforcementType(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                limits.stream().map(this::toLimitResponse).toList()
        );
    }

    private PolicyRuleLimitResponse toLimitResponse(PolicyRuleLimit entity) {
        return new PolicyRuleLimitResponse(
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getLimitAmount()
        );
    }
}
