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
    @Mock private com.expense_management_service.service.ReceiptDuplicateService receiptDuplicateService;
    @Mock private com.expense_management_service.service.CrossDepartmentAllocationService crossDepartmentAllocationService;
    @Mock private PolicyViolationRepository policyViolationRepository;
    @Mock private com.expense_management_service.repository.ExpenseLineItemRepository expenseLineItemRepository;

    private InterimPolicyEvaluationGatewayImpl gateway;

    private ExpenseReport report(UUID reportId, ExpenseLineItem... items) {
        return ExpenseReport.builder().reportId(reportId).clientId(UUID.randomUUID()).expenseLineItems(List.of(items)).build();
    }

    private PolicyViolation violation(PolicyEnforcementType enforcement, String justification) {
        return PolicyViolation.builder().violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).severity(PolicySeverity.WARN)
                .enforcementType(enforcement).message("Over the Meals limit by INR 700.00")
                .justification(justification).build();
    }

    private PolicyDecision evaluateWith(PolicyViolation... violations) {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(UUID.randomUUID())
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.CASH).build();
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(violations));
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of(violations));
        when(policyViolationRepository.findByLineItem_Report_ReportId(eq(reportId))).thenReturn(List.of(violations));
        return gateway.evaluate(report(reportId, lineItem));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gateway = new InterimPolicyEvaluationGatewayImpl(policyEvaluator, policyViolationRepository, new PolicyViolationMapper(),
                receiptDuplicateService, crossDepartmentAllocationService, expenseLineItemRepository);
    }

    @Test
    void evaluate_blocksSubmission_whenTheTripHasNoProjectOrClient() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport noReference = ExpenseReport.builder().reportId(reportId).expenseLineItems(List.of(paidLine())).build();
        stubNoViolations();

        PolicyDecision decision = gateway.evaluate(noReference);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason()).contains("project or client");
    }

    @Test
    void evaluate_blocksSubmission_whenALineHasNoPaymentMethod() {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem unpaidMethod = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build();
        stubNoViolations();

        PolicyDecision decision = gateway.evaluate(report(reportId, unpaidMethod));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason()).contains("how you paid");
    }

    @Test
    void evaluate_blocksSubmission_whenOtherPaymentHasNoExplanation() {
        UUID reportId = UUID.randomUUID();
        ExpenseLineItem other = ExpenseLineItem.builder().lineItemId(UUID.randomUUID())
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.OTHER).build();
        stubNoViolations();

        PolicyDecision decision = gateway.evaluate(report(reportId, other));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason()).contains("'Other'");
    }

    private ExpenseLineItem paidLine() {
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID())
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.CASH).build();
    }

    private void stubNoViolations() {
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of());
    }

    @Test
    void evaluate_blocksSubmission_whenMandatoryReceiptIsMissing() {
        UUID reportId = UUID.randomUUID();
        com.expense_management_service.entity.ExpenseCategory meals = com.expense_management_service.entity.ExpenseCategory.builder()
                .categoryName("Meals").receiptRequired(true).build();
        ExpenseLineItem line = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).category(meals)
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.CASH)
                .expenseDate(java.time.LocalDate.of(2026, 11, 5)).build();
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of());

        PolicyDecision decision = gateway.evaluate(report(reportId, line));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason()).contains("mandatory receipt").contains("Meals");
    }

    @Test
    void evaluate_blocksSubmission_whenTheSameExpenseIsAlreadyClaimedOnAnotherReport() {
        UUID reportId = UUID.randomUUID();
        UUID currencyId = UUID.randomUUID();
        java.time.LocalDate date = java.time.LocalDate.of(2026, 11, 5);
        ExpenseLineItem line = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).merchantName("Taj Hotel")
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.CASH)
                .expenseDate(date).amount(new java.math.BigDecimal("2500.00"))
                .currency(com.expense_management_service.entity.Currency.builder().currencyId(currencyId).build()).build();
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of());
        when(expenseLineItemRepository.findActiveClaimsDuplicating(any(), eq(reportId), eq("Taj Hotel"), eq(date),
                eq(new java.math.BigDecimal("2500.00")), eq(currencyId), any()))
                .thenReturn(List.of(ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).build()));

        PolicyDecision decision = gateway.evaluate(report(reportId, line));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason()).contains("already claimed").contains("Taj Hotel");
    }

    @Test
    void evaluate_blocksSubmission_whenAReceiptWasAlreadyClaimed_evenWithNoPolicyViolations() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport report = report(reportId, ExpenseLineItem.builder().lineItemId(UUID.randomUUID())
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.CASH).build());
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of());
        when(receiptDuplicateService.findBlockingMatchesForReport(report)).thenReturn(List.of(
                new com.expense_management_service.service.ReceiptDuplicateService.DuplicateMatch(
                        UUID.randomUUID(), "bill.jpg", UUID.randomUUID(),
                        com.expense_management_service.service.ReceiptDuplicateService.MatchReason.SAME_FILE,
                        UUID.randomUUID(), UUID.randomUUID(), "ER-0042", false, false,
                        "This exact receipt file has already been claimed on another employee's expense report.")));

        PolicyDecision decision = gateway.evaluate(report);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason())
                .contains("already been claimed")
                .contains("Receipt 'bill.jpg'")
                .doesNotContain("ER-0042");
    }

    @Test
    void evaluate_blocksSubmission_whenACrossDepartmentChargeHasNoReason() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport report = report(reportId, ExpenseLineItem.builder().lineItemId(UUID.randomUUID())
                .employeePaymentMethod(com.expense_management_service.enums.EmployeePaymentMethod.CASH).build());
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of());
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());
        when(policyViolationRepository.saveAll(any())).thenReturn(List.of());
        when(receiptDuplicateService.findBlockingMatchesForReport(report)).thenReturn(List.of());
        when(crossDepartmentAllocationService.findLinesMissingReason(report))
                .thenReturn(List.of("Expense 'Spice Route' on 2026-10-10 is charged to HR Programs, outside your department."));

        PolicyDecision decision = gateway.evaluate(report);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockedReason()).contains("HR Programs");
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
