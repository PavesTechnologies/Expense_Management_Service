package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.FinanceEligibilityResult;
import com.expense_management_service.service.FinanceVerificationEligibilityChecker;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultFinanceVerificationEligibilityCheckerImpl implements FinanceVerificationEligibilityChecker {

    private static final String GL_ACCOUNT_STATUS_ACTIVE = "ACTIVE";

    private final PolicyViolationRepository policyViolationRepository;

    @Override
    public FinanceEligibilityResult check(ExpenseLineItem lineItem) {
        ExpenseCategory category = lineItem.getCategory();

        if (Boolean.TRUE.equals(category.getReceiptRequired()) && lineItem.getReceipts().isEmpty()) {
            return FinanceEligibilityResult.blocked("Receipt is missing.");
        }

        boolean hasUnresolvedPolicyException = policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId()).stream()
                .anyMatch(this::isUnresolved);
        if (hasUnresolvedPolicyException) {
            return FinanceEligibilityResult.blocked("Policy exception has not been resolved.");
        }

        GlAccount glAccount = category.getGlAccount();
        if (glAccount == null || !GL_ACCOUNT_STATUS_ACTIVE.equalsIgnoreCase(glAccount.getStatus())) {
            return FinanceEligibilityResult.blocked(
                    "Expense category \"" + category.getCategoryName() + "\" is mapped to an inactive GL Account.");
        }

        return FinanceEligibilityResult.ok();
    }

    /** WARN with no justification, or any BLOCK - the same "unresolved" definition the Finance Verification Phase 0 decision settled on. */
    private boolean isUnresolved(PolicyViolation violation) {
        if (violation.getEnforcementType() == PolicyEnforcementType.BLOCK) {
            return true;
        }
        return violation.getEnforcementType() == PolicyEnforcementType.WARN && violation.getJustification() == null;
    }
}
