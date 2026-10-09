package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.PolicyDecision;
import com.expense_management_service.service.PolicyEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Submission gate: a policy violation (WARN or BLOCK) stops submission only until the employee has
 * explained it; once every violation is justified the report goes through for the approver and
 * Finance to decide.
 */
@ExtendWith(MockitoExtension.class)
class InterimPolicyEvaluationGatewayImplTest {

    @Mock private PolicyEvaluator policyEvaluator;
    @Mock private PolicyViolationRepository policyViolationRepository;

    private InterimPolicyEvaluationGatewayImpl gateway;

    private ExpenseReport report(UUID reportId, ExpenseLineItem... items) {
        return ExpenseReport.builder().reportId(reportId).expenseLineItems(List.of(items)).build();
    }

    private PolicyViolation violation(PolicyEnforcementType enforcement, String justification) {
        return PolicyViolation.builder().violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).severity(PolicySeverity.WARN)
                .enforcementType(enforcement).message("Over the Meals limit by INR 700.00")
                .justification(justification).build();
    }

    private PolicyDecision evaluateWith(PolicyViolation... violations) {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(violations));
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of(violations));
        when(policyViolationRepository.findByLineItem_Report_ReportId(eq(reportId))).thenReturn(List.of(violations));
        return gateway.evaluate(report(reportId, lineItem));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gateway = new InterimPolicyEvaluationGatewayImpl(policyEvaluator, policyViolationRepository, new PolicyViolationMapper());
    }

    @Test
    void evaluate_allowsSubmission_whenNoViolations() {
        PolicyDecision decision = evaluateWith();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.blockedReason()).isNull();
    }

    @Test
    void evaluate_blocksSubmission_whenAViolationIsNotExplained() {
        PolicyDecision decision = evaluateWith(violation(PolicyEnforcementType.WARN, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason())
                .contains("Explain each policy violation")
                .contains("Over the Meals limit by INR 700.00");
    }

    @Test
    void evaluate_blocksSubmission_whenJustificationIsBlank() {
        PolicyDecision decision = evaluateWith(violation(PolicyEnforcementType.BLOCK, "   "));

        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void evaluate_allowsSubmission_whenEveryViolationIsExplained_evenBlock() {
        PolicyDecision decision = evaluateWith(
                violation(PolicyEnforcementType.WARN, "Client dinner for four, approved by the account lead"),
                violation(PolicyEnforcementType.BLOCK, "Only hotel available near the client site that week"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.violations()).hasSize(2);
    }
}
