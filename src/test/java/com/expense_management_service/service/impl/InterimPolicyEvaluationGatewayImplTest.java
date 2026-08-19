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
 * Covers the Finance Verification Phase 0 fix: BLOCK-severity violations must actually block
 * submission, not just WARN. Before this fix, {@code evaluate()} always returned {@code
 * allowed = true} regardless of {@code PolicyEnforcementType.BLOCK}.
 */
@ExtendWith(MockitoExtension.class)
class InterimPolicyEvaluationGatewayImplTest {

    @Mock private PolicyEvaluator policyEvaluator;
    @Mock private PolicyViolationRepository policyViolationRepository;

    private InterimPolicyEvaluationGatewayImpl gateway;

    private ExpenseReport report(UUID reportId, ExpenseLineItem... items) {
        return ExpenseReport.builder().reportId(reportId).expenseLineItems(List.of(items)).build();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gateway = new InterimPolicyEvaluationGatewayImpl(policyEvaluator, policyViolationRepository, new PolicyViolationMapper());
    }

    @Test
    void evaluate_allowsSubmission_whenNoViolations() {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        ExpenseReport report = report(reportId, lineItem);

        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of());
        when(policyViolationRepository.existsByLineItem_Report_ReportIdAndEnforcementType(eq(reportId), eq(PolicyEnforcementType.BLOCK)))
                .thenReturn(false);

        PolicyDecision decision = gateway.evaluate(report);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_allowsSubmission_whenOnlyWarnViolationsExist() {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        ExpenseReport report = report(reportId, lineItem);
        PolicyViolation warn = PolicyViolation.builder().violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).severity(PolicySeverity.WARN)
                .enforcementType(PolicyEnforcementType.WARN).build();

        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(warn));
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of(warn));
        when(policyViolationRepository.existsByLineItem_Report_ReportIdAndEnforcementType(eq(reportId), eq(PolicyEnforcementType.BLOCK)))
                .thenReturn(false);

        PolicyDecision decision = gateway.evaluate(report);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.violations()).hasSize(1);
    }

    @Test
    void evaluate_blocksSubmission_whenBlockViolationExists() {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        ExpenseReport report = report(reportId, lineItem);
        PolicyViolation block = PolicyViolation.builder().violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).severity(PolicySeverity.WARN)
                .enforcementType(PolicyEnforcementType.BLOCK).build();

        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(block));
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of(block));
        when(policyViolationRepository.existsByLineItem_Report_ReportIdAndEnforcementType(eq(reportId), eq(PolicyEnforcementType.BLOCK)))
                .thenReturn(true);

        PolicyDecision decision = gateway.evaluate(report);

        assertThat(decision.allowed()).isFalse();
    }
}
