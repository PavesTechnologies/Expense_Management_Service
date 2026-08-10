package com.expense_management_service.service.impl;

import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyRuleLimit;
import com.expense_management_service.entity.PolicySeverityThreshold;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyOverageTier;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.PolicyRuleLimitRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.repository.PolicySeverityThresholdRepository;
import com.expense_management_service.service.PolicyAssignmentResolver;
import com.expense_management_service.service.PolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers every {@code PolicyRuleType} branch plus the never-throw contract documented on
 * {@link com.expense_management_service.service.PolicyEvaluator} — a malformed rule or a
 * repository failure must degrade to "no violation", never an exception, regardless of whether the
 * rule in question is Warn or Block enforcement (this evaluator only ever produces a violation
 * record; it never itself decides what a Block enforcement type does with it — that's {@code
 * ApprovalWorkflowServiceImpl}'s job alone).
 */
@ExtendWith(MockitoExtension.class)
class DefaultPolicyEvaluatorTest {

    @Mock
    private PolicyRuleRepository policyRuleRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private PolicyAssignmentResolver policyAssignmentResolver;
    @Mock
    private PolicyRuleLimitRepository policyRuleLimitRepository;
    @Mock
    private PolicySeverityThresholdRepository policySeverityThresholdRepository;
    @Mock
    private PolicyVersionService policyVersionService;

    private DefaultPolicyEvaluator evaluator;

    private UUID categoryId;
    private ExpenseCategory category;
    private ExpenseReport report;
    private Policy policy;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultPolicyEvaluator(policyRuleRepository, expenseLineItemRepository, policyAssignmentResolver,
                policyRuleLimitRepository, policySeverityThresholdRepository, policyVersionService);
        categoryId = UUID.randomUUID();
        category = ExpenseCategory.builder().categoryId(categoryId).categoryName("Travel").status("ACTIVE").build();
        report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("5100014").build();
        policy = Policy.builder().policyId(UUID.randomUUID()).policyName("Default Policy").status("ACTIVE").build();
        lenient().when(policyAssignmentResolver.resolve("5100014")).thenReturn(policy);
        lenient().when(policyVersionService.getCurrentVersion(policy.getPolicyId())).thenReturn(1);
    }

    private ExpenseLineItem.ExpenseLineItemBuilder lineItem() {
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report).category(category)
                .expenseDate(LocalDate.now()).amount(new BigDecimal("100"));
    }

    private PolicyRule rule(PolicyRuleType type, String ruleValue) {
        return rule(type, ruleValue, PolicyEnforcementType.WARN);
    }

    private PolicyRule rule(PolicyRuleType type, String ruleValue, PolicyEnforcementType enforcementType) {
        return PolicyRule.builder().policyId(UUID.randomUUID()).category(category).ruleType(type)
                .ruleValue(ruleValue).severity(PolicySeverity.WARN).enforcementType(enforcementType).status("ACTIVE").build();
    }

    private void stubRules(PolicyRule... rules) {
        when(policyRuleRepository.findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(policy.getPolicyId(), categoryId, "ACTIVE"))
                .thenReturn(List.of(rules));
    }

    @Test
    void evaluate_returnsEmpty_whenCategoryIsNull() {
        ExpenseLineItem item = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void amountLimit_fires_whenOverLimit() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "50"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("100")).build();

        List<PolicyViolation> violations = evaluator.evaluate(item);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(PolicyRuleType.AMOUNT_LIMIT);
        assertThat(violations.get(0).getSeverity()).isEqualTo(PolicySeverity.WARN);
    }

    // ---- Phase 5: versioning - "old expenses are judged against the version active when they
    // were submitted, not retroactively" made provable rather than just incidentally true. --------

    @Test
    void evaluate_stampsTheCurrentlyResolvedPolicyVersion() {
        stubRules(rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        when(policyVersionService.getCurrentVersion(policy.getPolicyId())).thenReturn(5);
        ExpenseLineItem item = lineItem().description(null).build();

        assertThat(evaluator.evaluate(item).get(0).getPolicyVersionNumber()).isEqualTo(5);
    }

    @Test
    void evaluate_historicalAccuracy_priorViolationsKeepTheirStampedVersion_afterALaterPolicyEdit() {
        // The exact scenario the spec's versioning guarantee protects: an expense evaluated under
        // v1, then an Admin edits the policy (activating v2) - the expense's ALREADY-recorded
        // violation must not be silently rewritten to claim v2 caused it.
        stubRules(rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        when(policyVersionService.getCurrentVersion(policy.getPolicyId())).thenReturn(1);
        ExpenseLineItem januaryExpense = lineItem().description(null).build();

        PolicyViolation januaryViolation = evaluator.evaluate(januaryExpense).get(0);
        assertThat(januaryViolation.getPolicyVersionNumber()).isEqualTo(1);

        // Admin edits the policy - PolicyRuleServiceImpl would call activateNewVersion, bumping
        // the current version to 2. Simulate that here by re-stubbing the resolver's answer.
        when(policyVersionService.getCurrentVersion(policy.getPolicyId())).thenReturn(2);

        // januaryViolation itself is untouched by this - nothing re-evaluates it just because the
        // policy changed. A *new* evaluation (e.g. a fresh line item, or a resubmission) is what
        // picks up the new version - and it correctly gets a different stamp.
        ExpenseLineItem juneExpense = lineItem().lineItemId(UUID.randomUUID()).description(null).build();
        PolicyViolation juneViolation = evaluator.evaluate(juneExpense).get(0);

        assertThat(januaryViolation.getPolicyVersionNumber()).isEqualTo(1);
        assertThat(juneViolation.getPolicyVersionNumber()).isEqualTo(2);
    }

    @Test
    void amountLimit_doesNotFire_whenUnderLimit() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "500"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("100")).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void amountLimit_fallsBackToAmount_whenBaseAmountNull() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "50"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("100")).baseAmount(null).build();

        assertThat(evaluator.evaluate(item)).hasSize(1);
    }

    @Test
    void amountLimit_usesBaseAmount_whenPresent() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "50"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("10")).baseAmount(new BigDecimal("100")).build();

        assertThat(evaluator.evaluate(item)).hasSize(1);
    }

    @Test
    void amountLimit_denormalizesBlockEnforcementType_ontoTheViolation() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "50", PolicyEnforcementType.BLOCK));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("100")).build();

        List<PolicyViolation> violations = evaluator.evaluate(item);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getEnforcementType()).isEqualTo(PolicyEnforcementType.BLOCK);
    }

    // ---- Phase 4: severity tiers & currency-scoped limits ------------------------------------

    @Test
    void amountLimit_populatesDeltaFields() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "1500"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("2200")).build();

        PolicyViolation violation = evaluator.evaluate(item).get(0);

        assertThat(violation.getLimitValue()).isEqualByComparingTo("1500");
        assertThat(violation.getActualValue()).isEqualByComparingTo("2200");
        assertThat(violation.getOveragePercent()).isEqualByComparingTo("46.67");
    }

    @Test
    void amountLimit_usesDefaultBands_forMinorTier_whenNeitherPolicyNorGlobalThresholdsConfigured() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "1000"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("1100")).build(); // 10% over

        assertThat(evaluator.evaluate(item).get(0).getSeverityTier()).isEqualTo(PolicyOverageTier.MINOR);
    }

    @Test
    void amountLimit_usesDefaultBands_forModerateTier_matchingTheOriginalSpecExample() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "1500"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("2200")).build(); // 46.67% over

        assertThat(evaluator.evaluate(item).get(0).getSeverityTier()).isEqualTo(PolicyOverageTier.MODERATE);
    }

    @Test
    void amountLimit_usesDefaultBands_forSevereTier_whenOverageAtOrAbove60Percent() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "1000"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("1600")).build(); // 60% over

        assertThat(evaluator.evaluate(item).get(0).getSeverityTier()).isEqualTo(PolicyOverageTier.SEVERE);
    }

    @Test
    void amountLimit_prefersConfiguredPolicyThresholds_overDefaultBands() {
        UUID policyId = UUID.randomUUID();
        Policy scopedPolicy = Policy.builder().policyId(policyId).policyName("Strict Policy").status("ACTIVE").build();
        PolicyRule rule = PolicyRule.builder().policyId(UUID.randomUUID()).category(category).ruleType(PolicyRuleType.AMOUNT_LIMIT)
                .ruleValue("1000").severity(PolicySeverity.WARN).enforcementType(PolicyEnforcementType.WARN)
                .policy(scopedPolicy).status("ACTIVE").build();
        when(policyRuleRepository.findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(policy.getPolicyId(), categoryId, "ACTIVE"))
                .thenReturn(List.of(rule));
        // A configured band that classifies 10% over as SEVERE - the opposite of what the default bands would say.
        PolicySeverityThreshold strictBand = PolicySeverityThreshold.builder().thresholdId(UUID.randomUUID()).policy(scopedPolicy)
                .tier(PolicyOverageTier.SEVERE).minPercentOver(BigDecimal.ZERO).maxPercentOver(null).build();
        when(policySeverityThresholdRepository.findByPolicy_PolicyIdOrderByMinPercentOverAsc(policyId)).thenReturn(List.of(strictBand));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("1100")).build(); // 10% over

        assertThat(evaluator.evaluate(item).get(0).getSeverityTier()).isEqualTo(PolicyOverageTier.SEVERE);
    }

    @Test
    void amountLimit_fallsBackToFlatRuleValue_whenNoCurrencyLimitsConfigured() {
        // No PolicyRuleLimit rows stubbed (findByPolicyRule_PolicyId defaults to empty) - proves the
        // legacy flat-limit path (ruleValue + baseAmount) is exactly what still runs for every
        // pre-existing rule, unless an Admin explicitly opts it into currency-table mode.
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "50"));
        ExpenseLineItem item = lineItem().amount(new BigDecimal("10")).baseAmount(new BigDecimal("100")).build();

        List<PolicyViolation> violations = evaluator.evaluate(item);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getLimitValue()).isEqualByComparingTo("50");
        assertThat(violations.get(0).getActualValue()).isEqualByComparingTo("100");
    }

    @Test
    void amountLimit_usesCurrencyTable_whenLimitsConfiguredForTheLineItemsCurrency() {
        PolicyRule rule = rule(PolicyRuleType.AMOUNT_LIMIT, "999999"); // flat ruleValue deliberately irrelevant once opted in
        stubRules(rule);
        Currency inr = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("INR").decimalPlaces(2).build();
        PolicyRuleLimit inrLimit = PolicyRuleLimit.builder().limitId(UUID.randomUUID()).policyRule(rule)
                .currency(inr).limitAmount(new BigDecimal("1500")).build();
        when(policyRuleLimitRepository.findByPolicyRule_PolicyId(rule.getPolicyId())).thenReturn(List.of(inrLimit));
        ExpenseLineItem item = lineItem().currency(inr).amount(new BigDecimal("2200")).baseAmount(new BigDecimal("2200")).build();

        List<PolicyViolation> violations = evaluator.evaluate(item);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getLimitValue()).isEqualByComparingTo("1500");
        assertThat(violations.get(0).getCurrency()).isEqualTo(inr);
    }

    @Test
    void amountLimit_isSilent_whenCurrencyTableModeButNoLimitConfiguredForThisCurrency() {
        PolicyRule rule = rule(PolicyRuleType.AMOUNT_LIMIT, "50");
        stubRules(rule);
        Currency inr = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("INR").decimalPlaces(2).build();
        Currency usd = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("USD").decimalPlaces(2).build();
        PolicyRuleLimit inrLimit = PolicyRuleLimit.builder().limitId(UUID.randomUUID()).policyRule(rule)
                .currency(inr).limitAmount(new BigDecimal("1500")).build();
        when(policyRuleLimitRepository.findByPolicyRule_PolicyId(rule.getPolicyId())).thenReturn(List.of(inrLimit));
        // Line item is in USD, but only an INR limit is configured for this rule.
        ExpenseLineItem item = lineItem().currency(usd).amount(new BigDecimal("5000")).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void amountLimit_skipsRule_whenRuleValueNotNumeric() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "abc"));
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void malformedRule_doesNotSuppressOtherRules() {
        stubRules(rule(PolicyRuleType.AMOUNT_LIMIT, "abc"), rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        ExpenseLineItem item = lineItem().description(null).build();

        List<PolicyViolation> violations = evaluator.evaluate(item);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(PolicyRuleType.MISSING_DESCRIPTION);
    }

    @Test
    void receiptRequired_fires_whenNoReceiptAttached() {
        category.setReceiptRequired(true);
        stubRules(rule(PolicyRuleType.RECEIPT_REQUIRED, null));
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).hasSize(1);
    }

    @Test
    void receiptRequired_doesNotFire_whenReceiptRequiredIsNull() {
        category.setReceiptRequired(null);
        stubRules(rule(PolicyRuleType.RECEIPT_REQUIRED, null));
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void backdatedDays_fires_whenOlderThanConfiguredDays() {
        stubRules(rule(PolicyRuleType.BACKDATED_DAYS, "5"));
        ExpenseLineItem item = lineItem().expenseDate(LocalDate.now().minusDays(10)).build();

        assertThat(evaluator.evaluate(item)).hasSize(1);
    }

    @Test
    void backdatedDays_doesNotFire_whenWithinWindow() {
        stubRules(rule(PolicyRuleType.BACKDATED_DAYS, "5"));
        ExpenseLineItem item = lineItem().expenseDate(LocalDate.now().minusDays(1)).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void backdatedDays_skipsRule_whenRuleValueNotNumeric() {
        stubRules(rule(PolicyRuleType.BACKDATED_DAYS, "abc"));
        ExpenseLineItem item = lineItem().expenseDate(LocalDate.now().minusDays(100)).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void missingDescription_fires_whenBlank() {
        stubRules(rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        ExpenseLineItem item = lineItem().description("   ").build();

        assertThat(evaluator.evaluate(item)).hasSize(1);
    }

    @Test
    void missingDescription_doesNotFire_whenPresent() {
        stubRules(rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        ExpenseLineItem item = lineItem().description("Client dinner").build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void duplicateExpense_fires_whenAnotherMatchingLineItemExists() {
        stubRules(rule(PolicyRuleType.DUPLICATE_EXPENSE, null));
        ExpenseLineItem item = lineItem().expenseDate(LocalDate.now()).amount(new BigDecimal("100")).build();
        ExpenseLineItem other = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        when(expenseLineItemRepository.findByReport_EmployeeIdAndCategory_CategoryIdAndExpenseDateAndAmount(
                report.getEmployeeId(), categoryId, item.getExpenseDate(), item.getAmount()))
                .thenReturn(List.of(other));

        List<PolicyViolation> violations = evaluator.evaluate(item);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(PolicyRuleType.DUPLICATE_EXPENSE);
    }

    @Test
    void duplicateExpense_excludesSelf() {
        stubRules(rule(PolicyRuleType.DUPLICATE_EXPENSE, null));
        ExpenseLineItem item = lineItem().expenseDate(LocalDate.now()).amount(new BigDecimal("100")).build();
        when(expenseLineItemRepository.findByReport_EmployeeIdAndCategory_CategoryIdAndExpenseDateAndAmount(
                report.getEmployeeId(), categoryId, item.getExpenseDate(), item.getAmount()))
                .thenReturn(List.of(item));

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void nullRuleType_skipped_withoutThrowing() {
        PolicyRule neutralised = PolicyRule.builder().policyId(UUID.randomUUID()).category(category)
                .ruleType(null).severity(PolicySeverity.WARN).status("ACTIVE").build();
        stubRules(neutralised);
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void expiredRule_excluded() {
        PolicyRule expired = rule(PolicyRuleType.MISSING_DESCRIPTION, null);
        expired.setEffectiveTo(LocalDate.now().minusDays(1));
        stubRules(expired);
        ExpenseLineItem item = lineItem().description(null).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void notYetEffectiveRule_excluded() {
        PolicyRule future = rule(PolicyRuleType.MISSING_DESCRIPTION, null);
        future.setEffectiveFrom(LocalDate.now().plusDays(1));
        stubRules(future);
        ExpenseLineItem item = lineItem().description(null).build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void repositoryFailure_returnsEmpty_withoutThrowing() {
        when(policyRuleRepository.findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void resolverFailure_returnsEmpty_withoutThrowing() {
        when(policyAssignmentResolver.resolve("5100014")).thenThrow(new RuntimeException("db down"));
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }

    @Test
    void evaluate_neverReDerivesPrecedenceItself_alwaysDelegatesToResolver() {
        stubRules(rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        ExpenseLineItem item = lineItem().description(null).build();

        evaluator.evaluate(item);

        verify(policyAssignmentResolver).resolve("5100014");
        verify(policyRuleRepository).findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(policy.getPolicyId(), categoryId, "ACTIVE");
    }

    @Test
    void evaluate_scopesRulesToTheResolvedPolicyOnly_notJustCategory() {
        // Two employees, same category, different resolved policies: each must only see rules
        // scoped to their own policy - proving this is genuine policy-scoping, not a category-only
        // filter that happens to also take a policy id it ignores.
        Policy otherPolicy = Policy.builder().policyId(UUID.randomUUID()).policyName("Other Policy").status("ACTIVE").build();
        ExpenseReport otherReport = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("9999999").build();
        when(policyAssignmentResolver.resolve("9999999")).thenReturn(otherPolicy);

        stubRules(rule(PolicyRuleType.MISSING_DESCRIPTION, null));
        when(policyRuleRepository.findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(otherPolicy.getPolicyId(), categoryId, "ACTIVE"))
                .thenReturn(List.of());

        ExpenseLineItem mine = lineItem().description(null).build();
        ExpenseLineItem theirs = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(otherReport).category(category)
                .expenseDate(LocalDate.now()).amount(new BigDecimal("100")).description(null).build();

        assertThat(evaluator.evaluate(mine)).hasSize(1);
        assertThat(evaluator.evaluate(theirs)).isEmpty();
    }

    @Test
    void evaluate_resolvesStraightToDefault_whenReportIsNull() {
        Policy defaultPolicy = Policy.builder().policyId(UUID.randomUUID()).policyName("Default Policy").status("ACTIVE").build();
        when(policyAssignmentResolver.resolve(null)).thenReturn(defaultPolicy);
        when(policyRuleRepository.findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(defaultPolicy.getPolicyId(), categoryId, "ACTIVE"))
                .thenReturn(List.of(rule(PolicyRuleType.MISSING_DESCRIPTION, null)));
        ExpenseLineItem item = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).category(category)
                .expenseDate(LocalDate.now()).amount(new BigDecimal("100")).description(null).build();

        assertThat(evaluator.evaluate(item)).hasSize(1);
        verify(policyAssignmentResolver).resolve(null);
    }
}
