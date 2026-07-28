package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.mapper.ExpenseLineItemMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.ExchangeRateService;
import com.expense_management_service.service.ExpenseLineItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ExpenseLineItemServiceImpl implements ExpenseLineItemService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final CurrencyRepository currencyRepository;
    private final CostCenterRepository costCenterRepository;
    private final ProjectCacheRepository projectCacheRepository;
    private final ExchangeRateService exchangeRateService;
    private final CurrentUserService currentUserService;
    private final ExpenseLineItemMapper expenseLineItemMapper;

    @Override
    public ExpenseLineItemResponse create(UUID reportId, ExpenseLineItemRequest request) {
        ExpenseReport report = findReport(reportId);
        assertOwnerOrAdmin(report);
        assertReportEditable(report);

        ExpenseCategory category = findCategory(request.categoryId());
        if (!STATUS_ACTIVE.equalsIgnoreCase(category.getStatus())) {
            throw new IllegalArgumentException(
                    "Expense category " + category.getCategoryName() + " is not Active and cannot be assigned to a new line item");
        }
        assertExpenseDateValid(request.expenseDate());

        ExpenseLineItem entity = expenseLineItemMapper.toEntity(request);
        entity.setReport(report);
        entity.setCategory(category);
        entity.setLineStatus(STATUS_ACTIVE);
        applyRelations(entity, request);
        applyCurrencyConversion(entity, report);

        ExpenseLineItem saved = expenseLineItemRepository.save(entity);
        log.info("Added line item {} to expense report {}", saved.getLineItemId(), reportId);
        return toResponse(saved);
    }

    @Override
    public ExpenseLineItemResponse update(UUID reportId, UUID lineItemId, ExpenseLineItemRequest request) {
        ExpenseReport report = findReport(reportId);
        assertOwnerOrAdmin(report);
        assertReportEditable(report);
        ExpenseLineItem entity = findLineItem(reportId, lineItemId);

        ExpenseCategory category = findCategory(request.categoryId());
        boolean categoryChanged = entity.getCategory() == null
                || !entity.getCategory().getCategoryId().equals(category.getCategoryId());
        if (categoryChanged && !STATUS_ACTIVE.equalsIgnoreCase(category.getStatus())) {
            throw new IllegalArgumentException(
                    "Expense category " + category.getCategoryName() + " is not Active and cannot be assigned");
        }
        assertExpenseDateValid(request.expenseDate());

        expenseLineItemMapper.updateEntity(entity, request);
        entity.setCategory(category);
        applyRelations(entity, request);
        applyCurrencyConversion(entity, report);

        ExpenseLineItem saved = expenseLineItemRepository.save(entity);
        log.info("Updated line item {} on expense report {}", lineItemId, reportId);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseLineItemResponse getById(UUID reportId, UUID lineItemId) {
        ExpenseReport report = findReport(reportId);
        assertViewable(report);
        return toResponse(findLineItem(reportId, lineItemId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseLineItemResponse> getAllForReport(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        assertViewable(report);
        return expenseLineItemRepository.findByReport_ReportId(reportId).stream().map(this::toResponse).toList();
    }

    @Override
    public void delete(UUID reportId, UUID lineItemId) {
        ExpenseReport report = findReport(reportId);
        assertOwnerOrAdmin(report);
        assertReportEditable(report);
        ExpenseLineItem entity = findLineItem(reportId, lineItemId);
        expenseLineItemRepository.delete(entity);
        log.info("Deleted line item {} from expense report {}", lineItemId, reportId);
    }

    private void assertExpenseDateValid(LocalDate expenseDate) {
        if (expenseDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("expenseDate cannot be in the future");
        }
    }

    private void applyRelations(ExpenseLineItem entity, ExpenseLineItemRequest request) {
        entity.setCurrency(findActiveCurrency(request.currencyId()));

        UUID costCenterId = request.costCenterId();
        entity.setCostCenter(costCenterId == null ? null : findActiveCostCenter(costCenterId));

        UUID projectId = request.projectId();
        entity.setProject(projectId == null ? null : findProject(projectId));
    }

    /** Best-effort conversion to the report's currency — a missing FX rate does not block saving the line item. */
    private void applyCurrencyConversion(ExpenseLineItem entity, ExpenseReport report) {
        Currency reportCurrency = report.getCurrency();
        if (reportCurrency == null || entity.getCurrency().getCurrencyId().equals(reportCurrency.getCurrencyId())) {
            entity.setExchangeRate(BigDecimal.ONE);
            entity.setBaseAmount(entity.getAmount());
            return;
        }
        try {
            var rate = exchangeRateService.getHistoricalRate(
                    entity.getCurrency().getCurrencyId(), reportCurrency.getCurrencyId(), entity.getExpenseDate());
            entity.setExchangeRate(rate.rate());
            entity.setBaseAmount(entity.getAmount().multiply(rate.rate()).setScale(4, RoundingMode.HALF_UP));
        } catch (ResourceNotFoundException ex) {
            log.warn("No exchange rate available for {} -> {} as of {}; leaving base amount unconverted",
                    entity.getCurrency().getCurrencyCode(), reportCurrency.getCurrencyCode(), entity.getExpenseDate());
            entity.setExchangeRate(null);
            entity.setBaseAmount(null);
        }
    }

    private void assertOwnerOrAdmin(ExpenseReport report) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (hasRole(caller, RoleConstants.ADMIN)) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only modify line items on your own expense report");
        }
    }

    private void assertViewable(ExpenseReport report) {
        CurrentUser caller = currentUserService.getCurrentUser();
        boolean privileged = hasRole(caller, RoleConstants.ADMIN) || hasRole(caller, RoleConstants.FINANCE)
                || hasRole(caller, RoleConstants.MANAGER);
        if (privileged) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view line items on your own expense report");
        }
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    private void assertReportEditable(ExpenseReport report) {
        if (!report.getReportStatus().isEditable()) {
            throw new BusinessRuleViolationException(
                    "Line items cannot be added, edited, or deleted while the report is in status " + report.getReportStatus());
        }
    }

    private ExpenseCategory findCategory(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }

    private Currency findActiveCurrency(UUID currencyId) {
        Currency currency = currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
        if (currency.getStatus() != null && !STATUS_ACTIVE.equalsIgnoreCase(currency.getStatus())) {
            throw new IllegalArgumentException("Currency " + currency.getCurrencyCode() + " is not Active and cannot be selected");
        }
        return currency;
    }

    private CostCenter findActiveCostCenter(UUID costCenterId) {
        CostCenter costCenter = costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(costCenter.getStatus())) {
            throw new IllegalArgumentException("Cost center " + costCenter.getCostCenterCode() + " is not Active and cannot be selected");
        }
        return costCenter;
    }

    private ProjectCache findProject(UUID projectId) {
        return projectCacheRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectCache not found with id: " + projectId));
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private ExpenseLineItem findLineItem(UUID reportId, UUID lineItemId) {
        return expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ExpenseLineItem not found with id: " + lineItemId + " on report " + reportId));
    }

    private ExpenseLineItemResponse toResponse(ExpenseLineItem entity) {
        ExpenseCategory category = entity.getCategory();
        boolean categoryActive = category != null && STATUS_ACTIVE.equalsIgnoreCase(category.getStatus());
        return expenseLineItemMapper.toResponse(entity, categoryActive);
    }
}
