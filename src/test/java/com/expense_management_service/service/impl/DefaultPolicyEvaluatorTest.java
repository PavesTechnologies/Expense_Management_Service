package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
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
import static org.mockito.Mockito.when;

/**
 * Covers every {@code PolicyRuleType} branch plus the never-throw contract documented on
 * {@link com.expense_management_service.service.PolicyEvaluator} — EP05 is advisory-only, so a
 * malformed rule or a repository failure must degrade to "no violation", never an exception.
 */
@ExtendWith(MockitoExtension.class)
class DefaultPolicyEvaluatorTest {

    @Mock
    private PolicyRuleRepository policyRuleRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;

    private DefaultPolicyEvaluator evaluator;

    private UUID categoryId;
    private ExpenseCategory category;
    private ExpenseReport report;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultPolicyEvaluator(policyRuleRepository, expenseLineItemRepository);
        categoryId = UUID.randomUUID();
        category = ExpenseCategory.builder().categoryId(categoryId).categoryName("Travel").status("ACTIVE").build();
        report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("5100014").build();
    }

    private ExpenseLineItem.ExpenseLineItemBuilder lineItem() {
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report).category(category)
                .expenseDate(LocalDate.now()).amount(new BigDecimal("100"));
    }

    private PolicyRule rule(PolicyRuleType type, String ruleValue) {
        return PolicyRule.builder().policyId(UUID.randomUUID()).category(category).ruleType(type)
                .ruleValue(ruleValue).severity(PolicySeverity.WARN).status("ACTIVE").build();
    }

    private void stubRules(PolicyRule... rules) {
        when(policyRuleRepository.findByCategory_CategoryIdAndStatus(categoryId, "ACTIVE")).thenReturn(List.of(rules));
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
        when(policyRuleRepository.findByCategory_CategoryIdAndStatus(any(), any())).thenThrow(new RuntimeException("db down"));
        ExpenseLineItem item = lineItem().build();

        assertThat(evaluator.evaluate(item)).isEmpty();
    }
}
