package com.expense_management_service.service.impl;

import com.expense_management_service.common.CriteriaPatternEvaluator;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalFlowCriterion;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.CriterionOperator;
import com.expense_management_service.repository.ApprovalFlowRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.ApprovalFlowResolutionService;
import com.expense_management_service.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Priority-ordered flow matching (§1.1, §2.2-§2.4). Criteria fields DEPARTMENT/COST_CENTER match the
 * report's own cost center and the SUBMITTER's own department (EmployeeCache.departmentUuid) -
 * consistent with ApproverSourceType.DEPARTMENT_OWNER/REPORTING_MANAGER also being submitter-relative,
 * not cost-center-relative.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class DefaultApprovalFlowResolutionServiceImpl implements ApprovalFlowResolutionService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ApprovalFlowRepository approvalFlowRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateService exchangeRateService;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @Override
    public ApprovalFlow resolveMatchingFlow(ExpenseReport report) {
        BigDecimal baseCurrencyAmount = convertToBaseCurrency(report);

        for (ApprovalFlow flow : approvalFlowRepository.findByIsCatchAllFalseAndStatusOrderByPriorityAsc(STATUS_ACTIVE)) {
            if (matches(flow, report, baseCurrencyAmount)) {
                log.info("Report {} matched flow {} ({}) at priority {}",
                        report.getReportId(), flow.getFlowId(), flow.getName(), flow.getPriority());
                return flow;
            }
        }

        ApprovalFlow catchAll = approvalFlowRepository.findByIsCatchAllTrue()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No catch-all approval flow is configured - Admin must configure one before any report can be submitted"));
        log.info("Report {} matched no configured flow, falling back to catch-all {}", report.getReportId(), catchAll.getFlowId());
        return catchAll;
    }

    private boolean matches(ApprovalFlow flow, ExpenseReport report, BigDecimal baseCurrencyAmount) {
        if (flow.getCriteria().isEmpty()) {
            return false;
        }
        Map<Integer, Boolean> evaluatedCriteria = new HashMap<>();
        for (ApprovalFlowCriterion criterion : flow.getCriteria()) {
            evaluatedCriteria.put(criterion.getIndex(), evaluateCriterion(criterion, report, baseCurrencyAmount));
        }
        return CriteriaPatternEvaluator.evaluate(flow.getCriteriaPattern(), evaluatedCriteria);
    }

    private boolean evaluateCriterion(ApprovalFlowCriterion criterion, ExpenseReport report, BigDecimal baseCurrencyAmount) {
        return switch (criterion.getField()) {
            case AMOUNT -> evaluateAmount(criterion, baseCurrencyAmount);
            case CATEGORY -> evaluateCategory(criterion, report);
            case DEPARTMENT -> evaluateDepartment(criterion, report);
            case COST_CENTER -> evaluateCostCenter(criterion, report);
        };
    }

    private boolean evaluateAmount(ApprovalFlowCriterion criterion, BigDecimal baseCurrencyAmount) {
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(criterion.getValue().trim());
        } catch (NumberFormatException | NullPointerException ex) {
            log.warn("Criterion {} has a non-numeric AMOUNT value '{}' - treating as no-match", criterion.getCriterionId(), criterion.getValue());
            return false;
        }
        int comparison = baseCurrencyAmount.compareTo(threshold);
        return switch (criterion.getOperator()) {
            case EQUALS -> comparison == 0;
            case NOT_EQUALS -> comparison != 0;
            case GREATER_THAN -> comparison > 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case LESS_THAN -> comparison < 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
        };
    }

    /** "Any line item matches" (§2.4, OR-aggregated across the report's line items, not stored). */
    private boolean evaluateCategory(ApprovalFlowCriterion criterion, ExpenseReport report) {
        boolean anyLineItemMatches = report.getExpenseLineItems().stream()
                .map(ExpenseLineItem::getCategory)
                .filter(java.util.Objects::nonNull)
                .anyMatch(category -> valueEquals(criterion.getValue(), category.getCategoryCode()));
        return applyEqualityOperator(criterion.getOperator(), anyLineItemMatches);
    }

    /** Submitter's own department (EmployeeCache.departmentUuid), not the cost center's department. */
    private boolean evaluateDepartment(ApprovalFlowCriterion criterion, ExpenseReport report) {
        boolean matches = employeeCacheRepository.findByEmployeeId(report.getEmployeeId())
                .map(employee -> valueEquals(criterion.getValue(), employee.getDepartmentUuid()))
                .orElse(false);
        return applyEqualityOperator(criterion.getOperator(), matches);
    }

    private boolean evaluateCostCenter(ApprovalFlowCriterion criterion, ExpenseReport report) {
        boolean matches = report.getCostCenter() != null
                && valueEquals(criterion.getValue(), report.getCostCenter().getCostCenterCode());
        return applyEqualityOperator(criterion.getOperator(), matches);
    }

    /** GREATER_THAN/LESS_THAN variants are meaningless for non-AMOUNT fields - config-time validation (ApprovalFlowServiceImpl) should already reject them; defensively treated as no-match here rather than throwing mid-resolution. */
    private boolean applyEqualityOperator(CriterionOperator operator, boolean rawMatches) {
        return switch (operator) {
            case EQUALS -> rawMatches;
            case NOT_EQUALS -> !rawMatches;
            default -> {
                log.warn("Operator {} is not valid for a non-AMOUNT field - treating as no-match", operator);
                yield false;
            }
        };
    }

    private boolean valueEquals(String criterionValue, String actualValue) {
        return criterionValue != null && criterionValue.equalsIgnoreCase(actualValue);
    }

    private BigDecimal convertToBaseCurrency(ExpenseReport report) {
        Currency baseCurrency = currencyRepository.findByCurrencyCode(baseCurrencyCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Configured base currency '" + baseCurrencyCode + "' does not exist in the Currency master table"));
        return exchangeRateService.convertAmount(
                report.getTotalAmount(), report.getCurrency().getCurrencyId(), baseCurrency.getCurrencyId(), LocalDate.now());
    }
}
