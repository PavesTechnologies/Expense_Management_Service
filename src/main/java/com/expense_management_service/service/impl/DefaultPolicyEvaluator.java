package com.expense_management_service.service.impl;

import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyRuleLimit;
import com.expense_management_service.entity.PolicySeverityThreshold;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyOverageTier;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.PolicyRuleLimitRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.repository.PolicySeverityThresholdRepository;
import com.expense_management_service.service.PolicyAssignmentResolver;
import com.expense_management_service.service.PolicyEvaluator;
import com.expense_management_service.service.PolicyVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>
 * Rule lookup is scoped to the line item's category <em>within</em> the one policy {@link
 * PolicyAssignmentResolver} resolves for its employee — {@code PolicyAssignmentResolver} is the
 * only place Individual/Group/Default precedence is decided; this class never re-derives it.
 * Every rule type's dispatch is unchanged from the pre-bundle version except {@code AMOUNT_LIMIT},
 * which additionally computes an overage percentage, a {@link
 * com.expense_management_service.enums.PolicyOverageTier}, and (when the rule has been opted into
 * per-currency mode) looks up a currency-specific limit instead of its flat {@code ruleValue} - see
 * {@link #checkAmountLimit}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultPolicyEvaluator implements PolicyEvaluator {

    private static final String STATUS_ACTIVE = "ACTIVE";

    /** Built-in fallback bands, used only when neither a policy-specific nor a global {@link PolicySeverityThreshold} set is configured - see {@link #resolveSeverityTier}. */
    private static final BigDecimal DEFAULT_MODERATE_THRESHOLD = BigDecimal.valueOf(30);
    private static final BigDecimal DEFAULT_SEVERE_THRESHOLD = BigDecimal.valueOf(60);

    private final PolicyRuleRepository policyRuleRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final PolicyAssignmentResolver policyAssignmentResolver;
    private final PolicyRuleLimitRepository policyRuleLimitRepository;
    private final PolicySeverityThresholdRepository policySeverityThresholdRepository;
    private final PolicyVersionService policyVersionService;

    @Override
    public List<PolicyViolation> evaluate(ExpenseLineItem lineItem) {
        List<PolicyViolation> violations = new ArrayList<>();
        ExpenseCategory category = lineItem.getCategory();
        if (category == null) {
            return violations;
        }

        List<PolicyRule> rules;
        int policyVersionNumber;
        try {
            String employeeId = lineItem.getReport() != null ? lineItem.getReport().getEmployeeId() : null;
            Policy policy = policyAssignmentResolver.resolve(employeeId);
            rules = policyRuleRepository.findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(
                    policy.getPolicyId(), category.getCategoryId(), STATUS_ACTIVE);
            // Read once per evaluation, off the same resolved policy - never re-derived per rule -
            // so every violation from this pass is stamped with the one version active right now.
            policyVersionNumber = policyVersionService.getCurrentVersion(policy.getPolicyId());
        } catch (Exception ex) {
            log.warn("Failed to resolve policy or load policy rules for category {} - skipping policy evaluation for line item {}",
                    category.getCategoryId(), lineItem.getLineItemId(), ex);
            return violations;
        }

        LocalDate today = LocalDate.now();
        for (PolicyRule rule : rules) {
            if (!isInEffect(rule, today)) {
                continue;
            }
            try {
                evaluateRule(lineItem, rule, policyVersionNumber).ifPresent(violations::add);
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

    private Optional<PolicyViolation> evaluateRule(ExpenseLineItem lineItem, PolicyRule rule, int policyVersionNumber) {
        PolicyRuleType type = rule.getRuleType();
        if (type == null) {
            // Neutralised by V3__policy_rule_cleanup.sql (a pre-existing row whose free-text
            // ruleType didn't match this enum) - not an error, just nothing to evaluate.
            log.warn("Policy rule {} has no recognised ruleType - skipping", rule.getPolicyId());
            return Optional.empty();
        }

        if (type == PolicyRuleType.AMOUNT_LIMIT) {
            return checkAmountLimit(lineItem, rule).map(details -> baseViolation(lineItem, rule, type, details.message(), policyVersionNumber)
                    .limitValue(details.limitValue())
                    .actualValue(details.actualValue())
                    .overagePercent(details.overagePercent())
                    .severityTier(details.severityTier())
                    .currency(details.currency())
                    .build());
        }

        String message = switch (type) {
            case RECEIPT_REQUIRED -> checkReceiptRequired(lineItem);
            case BACKDATED_DAYS -> checkBackdatedDays(lineItem, rule);
            case MISSING_DESCRIPTION -> checkMissingDescription(lineItem);
            case DUPLICATE_EXPENSE -> checkDuplicateExpense(lineItem);
            case AMOUNT_LIMIT -> null; // unreachable - handled above, kept only so the switch stays exhaustive
        };

        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(baseViolation(lineItem, rule, type, message, policyVersionNumber).build());
    }

    private PolicyViolation.PolicyViolationBuilder baseViolation(ExpenseLineItem lineItem, PolicyRule rule, PolicyRuleType type, String message, int policyVersionNumber) {
        return PolicyViolation.builder()
                .lineItem(lineItem)
                .policyRule(rule)
                .ruleType(type)
                .severity(rule.getSeverity())
                .enforcementType(rule.getEnforcementType())
                .message(message)
                .policyVersionNumber(policyVersionNumber)
                .detectedAt(LocalDateTime.now());
    }

    /** The five delta fields an AMOUNT_LIMIT violation carries - see {@code PolicyViolation}'s own javadoc on why every other rule type leaves them null. */
    private record AmountLimitDetails(String message, BigDecimal limitValue, BigDecimal actualValue,
                                       BigDecimal overagePercent, PolicyOverageTier severityTier, Currency currency) {
    }

    /**
     * A rule with any {@link PolicyRuleLimit} rows is in per-currency mode: the limit is looked up
     * for the line item's own currency and compared against its own (unconverted) amount, per the
     * spec's "limits are currency-specific, not converted" decision; no configured row for that
     * specific currency is silence, not a violation. A rule with zero rows is in legacy flat-limit
     * mode and behaves exactly as it always has - comparing {@code ruleValue} against the
     * base-currency-converted amount - so no existing rule's behavior changes until an Admin
     * explicitly adds a currency limit to it.
     */
    private Optional<AmountLimitDetails> checkAmountLimit(ExpenseLineItem lineItem, PolicyRule rule) {
        List<PolicyRuleLimit> limits = policyRuleLimitRepository.findByPolicyRule_PolicyId(rule.getPolicyId());
        return limits.isEmpty()
                ? checkAmountLimitFlat(lineItem, rule)
                : checkAmountLimitByCurrency(lineItem, rule, limits);
    }

    private Optional<AmountLimitDetails> checkAmountLimitFlat(ExpenseLineItem lineItem, PolicyRule rule) {
        BigDecimal limit;
        try {
            limit = new BigDecimal(rule.getRuleValue().trim());
        } catch (Exception ex) {
            log.warn("AMOUNT_LIMIT rule {} has a non-numeric ruleValue '{}' - skipping",
                    rule.getPolicyId(), rule.getRuleValue());
            return Optional.empty();
        }
        // baseAmount is null whenever FX conversion had no rate available (see
        // ExpenseLineItemServiceImpl.applyCurrencyConversion) - fall back to the raw amount rather
        // than skip the check entirely.
        BigDecimal amount = lineItem.getBaseAmount() != null ? lineItem.getBaseAmount() : lineItem.getAmount();
        if (amount == null || amount.compareTo(limit) <= 0) {
            return Optional.empty();
        }
        BigDecimal overagePercent = overagePercent(amount, limit);
        return Optional.of(new AmountLimitDetails(
                "Amount " + amount + " exceeds the configured limit of " + limit,
                limit, amount, overagePercent, resolveSeverityTier(rule, overagePercent), lineItem.getCurrency()));
    }

    private Optional<AmountLimitDetails> checkAmountLimitByCurrency(ExpenseLineItem lineItem, PolicyRule rule, List<PolicyRuleLimit> limits) {
        Currency currency = lineItem.getCurrency();
        if (currency == null) {
            return Optional.empty();
        }
        Optional<PolicyRuleLimit> match = limits.stream()
                .filter(l -> l.getCurrency() != null && l.getCurrency().getCurrencyId().equals(currency.getCurrencyId()))
                .findFirst();
        if (match.isEmpty()) {
            // No configured limit for this currency (e.g. a first-ever trip to a new country) -
            // the spec calls this silence, not a violation: the system defers to the human approver.
            return Optional.empty();
        }
        BigDecimal limit = match.get().getLimitAmount();
        BigDecimal amount = lineItem.getAmount();
        if (amount == null || amount.compareTo(limit) <= 0) {
            return Optional.empty();
        }
        BigDecimal overagePercent = overagePercent(amount, limit);
        return Optional.of(new AmountLimitDetails(
                "Amount " + amount + " exceeds the configured limit of " + limit,
                limit, amount, overagePercent, resolveSeverityTier(rule, overagePercent), currency));
    }

    private BigDecimal overagePercent(BigDecimal amount, BigDecimal limit) {
        return amount.subtract(limit).multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP);
    }

    /**
     * Prefers the resolved policy's own threshold bands; falls back to the global (policy-null)
     * bands if that policy has none configured; falls back to a hardcoded 0-30-60% band if neither
     * exists at all, so severity tiering works out of the box without requiring Admin configuration
     * first - mirroring how {@code enforcementType} defaults to {@code WARN} without configuration.
     */
    private PolicyOverageTier resolveSeverityTier(PolicyRule rule, BigDecimal overagePercent) {
        List<PolicySeverityThreshold> thresholds = rule.getPolicy() != null
                ? policySeverityThresholdRepository.findByPolicy_PolicyIdOrderByMinPercentOverAsc(rule.getPolicy().getPolicyId())
                : List.of();
        if (thresholds.isEmpty()) {
            thresholds = policySeverityThresholdRepository.findByPolicyIsNullOrderByMinPercentOverAsc();
        }
        for (PolicySeverityThreshold threshold : thresholds) {
            boolean aboveMin = overagePercent.compareTo(threshold.getMinPercentOver()) >= 0;
            boolean belowMax = threshold.getMaxPercentOver() == null || overagePercent.compareTo(threshold.getMaxPercentOver()) < 0;
            if (aboveMin && belowMax) {
                return threshold.getTier();
            }
        }
        return defaultSeverityTier(overagePercent);
    }

    private PolicyOverageTier defaultSeverityTier(BigDecimal overagePercent) {
        if (overagePercent.compareTo(DEFAULT_SEVERE_THRESHOLD) >= 0) {
            return PolicyOverageTier.SEVERE;
        }
        return overagePercent.compareTo(DEFAULT_MODERATE_THRESHOLD) >= 0 ? PolicyOverageTier.MODERATE : PolicyOverageTier.MINOR;
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
