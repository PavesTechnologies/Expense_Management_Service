package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalFlowCriterion;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import com.expense_management_service.repository.ApprovalFlowRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApprovalFlowResolutionServiceImplTest {

    @Mock private ApprovalFlowRepository approvalFlowRepository;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private ExchangeRateService exchangeRateService;

    private DefaultApprovalFlowResolutionServiceImpl service;

    private final UUID currencyId = UUID.randomUUID();
    private final Currency baseCurrency = Currency.builder().currencyId(currencyId).currencyCode("INR").build();

    @BeforeEach
    void setUp() {
        service = new DefaultApprovalFlowResolutionServiceImpl(approvalFlowRepository, employeeCacheRepository, currencyRepository, exchangeRateService);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        when(currencyRepository.findByCurrencyCode("INR")).thenReturn(Optional.of(baseCurrency));
    }

    private ExpenseReport reportWithAmount(BigDecimal amount) {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("5100001")
                .currency(baseCurrency).totalAmount(amount).expenseLineItems(List.of()).build();
    }

    private ApprovalFlow flowWithAmountCriterion(int priority, CriterionOperator operator, String value) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(UUID.randomUUID()).name("f" + priority).priority(priority).isCatchAll(false).criteriaPattern("1").build();
        flow.getCriteria().add(ApprovalFlowCriterion.builder().flow(flow).index(1).field(CriterionField.AMOUNT).operator(operator).value(value).build());
        return flow;
    }

    @Test
    void resolveMatchingFlow_returnsFirstMatchingFlowByPriority() {
        ExpenseReport report = reportWithAmount(new BigDecimal("15000"));
        when(exchangeRateService.convertAmount(any(), any(), any(), any())).thenReturn(new BigDecimal("15000"));
        ApprovalFlow lowFlow = flowWithAmountCriterion(1, CriterionOperator.GREATER_THAN, "10000");
        when(approvalFlowRepository.findByIsCatchAllFalseAndStatusOrderByPriorityAsc("ACTIVE")).thenReturn(List.of(lowFlow));

        ApprovalFlow resolved = service.resolveMatchingFlow(report);

        assertThat(resolved.getFlowId()).isEqualTo(lowFlow.getFlowId());
    }

    @Test
    void resolveMatchingFlow_fallsBackToCatchAll_whenNothingMatches() {
        ExpenseReport report = reportWithAmount(new BigDecimal("100"));
        when(exchangeRateService.convertAmount(any(), any(), any(), any())).thenReturn(new BigDecimal("100"));
        ApprovalFlow nonMatching = flowWithAmountCriterion(1, CriterionOperator.GREATER_THAN, "10000");
        when(approvalFlowRepository.findByIsCatchAllFalseAndStatusOrderByPriorityAsc("ACTIVE")).thenReturn(List.of(nonMatching));
        ApprovalFlow catchAll = ApprovalFlow.builder().flowId(UUID.randomUUID()).isCatchAll(true).build();
        when(approvalFlowRepository.findByIsCatchAllTrue()).thenReturn(Optional.of(catchAll));

        ApprovalFlow resolved = service.resolveMatchingFlow(report);

        assertThat(resolved.getFlowId()).isEqualTo(catchAll.getFlowId());
    }

    @Test
    void resolveMatchingFlow_throws_whenNothingMatchesAndNoCatchAllConfigured() {
        ExpenseReport report = reportWithAmount(new BigDecimal("100"));
        when(exchangeRateService.convertAmount(any(), any(), any(), any())).thenReturn(new BigDecimal("100"));
        when(approvalFlowRepository.findByIsCatchAllFalseAndStatusOrderByPriorityAsc("ACTIVE")).thenReturn(List.of());
        when(approvalFlowRepository.findByIsCatchAllTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveMatchingFlow(report))
                .isInstanceOf(com.expense_management_service.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void resolveMatchingFlow_matchesOnAnyLineItemCategory() {
        ExpenseCategory travel = ExpenseCategory.builder().categoryId(UUID.randomUUID()).categoryCode("TRAVEL").build();
        ExpenseCategory meals = ExpenseCategory.builder().categoryId(UUID.randomUUID()).categoryCode("MEALS").build();
        ExpenseLineItem travelLine = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).category(travel).build();
        ExpenseLineItem mealsLine = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).category(meals).build();
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("5100001")
                .currency(baseCurrency).totalAmount(new BigDecimal("500")).expenseLineItems(List.of(mealsLine, travelLine)).build();
        when(exchangeRateService.convertAmount(any(), any(), any(), any())).thenReturn(new BigDecimal("500"));

        ApprovalFlow flow = ApprovalFlow.builder().flowId(UUID.randomUUID()).priority(1).isCatchAll(false).criteriaPattern("1").build();
        flow.getCriteria().add(ApprovalFlowCriterion.builder().flow(flow).index(1).field(CriterionField.CATEGORY).operator(CriterionOperator.EQUALS).value("TRAVEL").build());
        when(approvalFlowRepository.findByIsCatchAllFalseAndStatusOrderByPriorityAsc("ACTIVE")).thenReturn(List.of(flow));

        ApprovalFlow resolved = service.resolveMatchingFlow(report);

        assertThat(resolved.getFlowId()).isEqualTo(flow.getFlowId());
    }

    @Test
    void resolveMatchingFlow_matchesOnSubmittersDepartment_notCostCenterDepartment() {
        UUID departmentUuid = UUID.randomUUID();
        ExpenseReport report = reportWithAmount(new BigDecimal("500"));
        when(exchangeRateService.convertAmount(any(), any(), any(), any())).thenReturn(new BigDecimal("500"));
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100001").departmentUuid(departmentUuid.toString()).build()));

        ApprovalFlow flow = ApprovalFlow.builder().flowId(UUID.randomUUID()).priority(1).isCatchAll(false).criteriaPattern("1").build();
        flow.getCriteria().add(ApprovalFlowCriterion.builder().flow(flow).index(1).field(CriterionField.DEPARTMENT).operator(CriterionOperator.EQUALS).value(departmentUuid.toString()).build());
        when(approvalFlowRepository.findByIsCatchAllFalseAndStatusOrderByPriorityAsc("ACTIVE")).thenReturn(List.of(flow));

        ApprovalFlow resolved = service.resolveMatchingFlow(report);

        assertThat(resolved.getFlowId()).isEqualTo(flow.getFlowId());
    }
}
