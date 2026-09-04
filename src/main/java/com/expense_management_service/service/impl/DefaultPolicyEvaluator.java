package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.service.PolicyEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link PolicyEvaluator}. Follows the {@code ApproverResolver}/{@code
 * DefaultApproverResolverImpl} shape rather than the CRUD service shape — a small dispatcher over
 * an enum, logging and skipping rather than throwing. See {@link PolicyEvaluator}'s javadoc for the
 * never-throw contract this class must uphold.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultPolicyEvaluator implements PolicyEvaluator {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PolicyRuleRepository policyRuleRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;

    @Override
    public List<PolicyViolation> evaluate(ExpenseLineItem lineItem) {
        List<PolicyViolation> violations = new ArrayList<>();
        ExpenseCategory category = lineItem.getCategory();
        if (category == null) {
            return violations;
        }

        List<PolicyRule> rules;
        try {
            rules = policyRuleRepository.findByCategory_CategoryIdAndStatus(category.getCategoryId(), STATUS_ACTIVE);
        } catch (Exception ex) {
            log.warn("Failed to load policy rules for category {} - skipping policy evaluation for line item {}",
                    category.getCategoryId(), lineItem.getLineItemId(), ex);
            return violations;
        }

        LocalDate today = LocalDate.now();
        for (PolicyRule rule : rules) {
            if (!isInEffect(rule, today)) {
                continue;
            }
            try {
                evaluateRule(lineItem, rule).ifPresent(violations::add);
            } catch (Exception ex) {
                log.warn("Policy rule {} ({}) failed to evaluate for line item {} - skipping this rule",
                        rule.getPolicyId(), rule.getRuleType(), lineItem.getLineItemId(), ex);
            }
        }
        return violations;
    }

    private boolean isInEffect(PolicyRule rule, LocalDate today) {
        if (rule.getEffectiveFrom() != null && today.isBefore(rule.getEffectiveFrom())) {
            return false;
        }
        return rule.getEffectiveTo() == null || !today.isAfter(rule.getEffectiveTo());
    }

    private Optional<PolicyViolation> evaluateRule(ExpenseLineItem lineItem, PolicyRule rule) {
        PolicyRuleType type = rule.getRuleType();
        if (type == null) {
            // Neutralised by V3__policy_rule_cleanup.sql (a pre-existing row whose free-text
            // ruleType didn't match this enum) - not an error, just nothing to evaluate.
            log.warn("Policy rule {} has no recognised ruleType - skipping", rule.getPolicyId());
            return Optional.empty();
        }

        String message = switch (type) {
            case AMOUNT_LIMIT -> checkAmountLimit(lineItem, rule);
            case RECEIPT_REQUIRED -> checkReceiptRequired(lineItem);
            case BACKDATED_DAYS -> checkBackdatedDays(lineItem, rule);
            case MISSING_DESCRIPTION -> checkMissingDescription(lineItem);
            case DUPLICATE_EXPENSE -> checkDuplicateExpense(lineItem);
        };

        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(PolicyViolation.builder()
                .lineItem(lineItem)
                .policyRule(rule)
                .ruleType(type)
                .severity(rule.getSeverity())
                .message(message)
                .detectedAt(LocalDateTime.now())
                .build());
    }

    private String checkAmountLimit(ExpenseLineItem lineItem, PolicyRule rule) {
        BigDecimal limit;
        try {
            limit = new BigDecimal(rule.getRuleValue().trim());
        } catch (Exception ex) {
            log.warn("AMOUNT_LIMIT rule {} has a non-numeric ruleValue '{}' - skipping",
                    rule.getPolicyId(), rule.getRuleValue());
            return null;
        }
        // baseAmount is null whenever FX conversion had no rate available (see
        // ExpenseLineItemServiceImpl.applyCurrencyConversion) - fall back to the raw amount rather
        // than skip the check entirely.
        BigDecimal amount = lineItem.getBaseAmount() != null ? lineItem.getBaseAmount() : lineItem.getAmount();
        if (amount == null || amount.compareTo(limit) <= 0) {
            return null;
        }
        return "Amount " + amount + " exceeds the configured limit of " + limit;
    }

    private String checkReceiptRequired(ExpenseLineItem lineItem) {
        ExpenseCategory category = lineItem.getCategory();
        boolean receiptRequired = category != null && Boolean.TRUE.equals(category.getReceiptRequired());
        boolean hasReceipt = lineItem.getReceipts() != null && !lineItem.getReceipts().isEmpty();
        if (!receiptRequired || hasReceipt) {
            return null;
        }
        return "Category " + category.getCategoryName() + " requires a receipt, but none is attached";
    }

    private String checkBackdatedDays(ExpenseLineItem lineItem, PolicyRule rule) {
        int maxDays;
        try {
            maxDays = Integer.parseInt(rule.getRuleValue().trim());
        } catch (Exception ex) {
            log.warn("BACKDATED_DAYS rule {} has a non-numeric ruleValue '{}' - skipping",
                    rule.getPolicyId(), rule.getRuleValue());
            return null;
        }
        if (lineItem.getExpenseDate() == null) {
            return null;
        }
        LocalDate cutoff = LocalDate.now().minusDays(maxDays);
        if (!lineItem.getExpenseDate().isBefore(cutoff)) {
            return null;
        }
        return "Expense date " + lineItem.getExpenseDate() + " is more than " + maxDays + " day(s) in the past";
    }

    private String checkMissingDescription(ExpenseLineItem lineItem) {
        String description = lineItem.getDescription();
        if (description != null && !description.isBlank()) {
            return null;
        }
        return "This expense is missing a description";
    }

    private String checkDuplicateExpense(ExpenseLineItem lineItem) {
        if (lineItem.getReport() == null || lineItem.getCategory() == null
                || lineItem.getExpenseDate() == null || lineItem.getAmount() == null) {
            return null;
        }
        boolean hasDuplicate = expenseLineItemRepository
                .findByReport_EmployeeIdAndCategory_CategoryIdAndExpenseDateAndAmount(
                        lineItem.getReport().getEmployeeId(), lineItem.getCategory().getCategoryId(),
                        lineItem.getExpenseDate(), lineItem.getAmount())
                .stream()
                .anyMatch(other -> !other.getLineItemId().equals(lineItem.getLineItemId()));
        if (!hasDuplicate) {
            return null;
        }
        return "Another expense with the same category, date, and amount already exists for this employee";
    }
}
