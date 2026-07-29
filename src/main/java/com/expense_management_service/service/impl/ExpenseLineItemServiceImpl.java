package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ReportStatusConstants;
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

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * The organization's single accounting/base currency (e.g. "INR") — every line item's
     * {@code baseAmount} is always expressed in this currency, regardless of what currency the
     * parent report happens to be denominated in. Same property {@code ExchangeRateServiceImpl}
     * uses for its refresh job, so there is exactly one source of truth for "the base currency."
     */
    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

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
        assertTaxAmountValid(request.amount(), request.taxAmount());

        ExpenseLineItem entity = expenseLineItemMapper.toEntity(request);
        entity.setReport(report);
        entity.setCategory(category);
        entity.setLineStatus(STATUS_ACTIVE);
        applyRelations(entity, request);
        applyCurrencyConversion(entity);
        applyNetAmount(entity);

        ExpenseLineItem saved = expenseLineItemRepository.save(entity);
        recalculateReportTotal(report);
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
        assertTaxAmountValid(request.amount(), request.taxAmount());

        expenseLineItemMapper.updateEntity(entity, request);
        entity.setCategory(category);
        applyRelations(entity, request);
        applyCurrencyConversion(entity);
        applyNetAmount(entity);

        ExpenseLineItem saved = expenseLineItemRepository.save(entity);
        recalculateReportTotal(report);
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
        recalculateReportTotal(report);
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

    /**
     * Converts the line item's native amount into the Organization Base Currency — always, and
     * regardless of what currency the parent report happens to be denominated in (the report's
     * {@code currency} is a display/reference field only and never affects this conversion).
     * VAL-09: the exchange-rate availability check applies per line item independently. A missing
     * rate is not silently tolerated — the line item cannot be priced in base currency without it,
     * so the save is rejected with a clear, actionable message.
     */
    private void applyCurrencyConversion(ExpenseLineItem entity) {
        Currency baseCurrency = findOrganizationBaseCurrency();
        if (entity.getCurrency().getCurrencyId().equals(baseCurrency.getCurrencyId())) {
            entity.setExchangeRate(BigDecimal.ONE);
            entity.setBaseAmount(entity.getAmount());
            return;
        }
        try {
            var rate = exchangeRateService.getHistoricalRate(
                    entity.getCurrency().getCurrencyId(), baseCurrency.getCurrencyId(), entity.getExpenseDate());
            entity.setExchangeRate(rate.rate());
            entity.setBaseAmount(entity.getAmount().multiply(rate.rate()).setScale(4, RoundingMode.HALF_UP));
        } catch (ResourceNotFoundException ex) {
            throw new BusinessRuleViolationException(
                    "No exchange rate is available to convert " + entity.getCurrency().getCurrencyCode() + " to "
                            + baseCurrency.getCurrencyCode() + " (the organization base currency) as of " + entity.getExpenseDate()
                            + ". Ask an Administrator to add this exchange rate before saving this line item.");
        }
    }

    /**
     * Resolves the organization's single accounting/base currency from {@link #baseCurrencyCode}.
     * A missing or inactive configured base currency is a system misconfiguration, not a client
     * error — every line item conversion depends on it, so it fails loudly rather than silently
     * falling back to something else.
     */
    private Currency findOrganizationBaseCurrency() {
        Currency baseCurrency = currencyRepository.findByCurrencyCodeIgnoreCase(baseCurrencyCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Organization base currency '" + baseCurrencyCode + "' is not configured in the Currency master table"));
        if (!STATUS_ACTIVE.equalsIgnoreCase(baseCurrency.getStatus())) {
            throw new IllegalStateException(
                    "Organization base currency '" + baseCurrencyCode + "' is configured but not Active");
        }
        return baseCurrency;
    }

    /** GST/VAT is captured exactly as printed on the receipt; a missing value is treated as zero. */
    private void applyNetAmount(ExpenseLineItem entity) {
        BigDecimal tax = entity.getTaxAmount() != null ? entity.getTaxAmount() : BigDecimal.ZERO;
        entity.setNetAmount(entity.getAmount().subtract(tax));
    }

    private void assertTaxAmountValid(BigDecimal amount, BigDecimal taxAmount) {
        if (taxAmount == null) {
            return;
        }
        if (taxAmount.signum() < 0) {
            throw new IllegalArgumentException("taxAmount cannot be negative");
        }
        if (taxAmount.compareTo(amount) > 0) {
            throw new IllegalArgumentException("taxAmount cannot exceed the total amount");
        }
    }

    /** Report-level totals are always presented in the Organization Base Currency, derived from every line item's converted baseAmount — never in the report's own display currency. */
    private void recalculateReportTotal(ExpenseReport report) {
        BigDecimal total = expenseLineItemRepository.sumBaseAmountByReportId(report.getReportId());
        report.setTotalAmount(total);
        // TODO: Implement in Policy Management Epic — reimbursableAmount depends on policy rules
        // (e.g. personal-portion exclusions) that are out of scope for multi-currency/VAT capture.
        expenseReportRepository.save(report);
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
        if (!ReportStatusConstants.isEditable(report.getReportStatus())) {
            throw new BusinessRuleViolationException(
                    "Line items cannot be added, edited, or deleted while the report is in status " + report.getReportStatus());
        }
    }

    private ExpenseCategory findCategory(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }

    /**
     * FR-1.4: the transaction currency must be in the organization's supported currency list.
     * An unsupported/deactivated currency is never silently rejected — the error tells the
     * employee to ask an Administrator to enable it, rather than leaving them guessing why the
     * save failed.
     */
    private Currency findActiveCurrency(UUID currencyId) {
        Currency currency = currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
        if (currency.getStatus() != null && !STATUS_ACTIVE.equalsIgnoreCase(currency.getStatus())) {
            throw new IllegalArgumentException(
                    "Currency " + currency.getCurrencyCode()
                            + " is not enabled for your organization. Please ask an Administrator to enable this currency before using it on a line item.");
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
        return expenseLineItemMapper.toResponse(entity, categoryActive, baseCurrencyCode);
    }
}
