package com.expense_management_service.service.impl;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.EmployeeInactiveException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.ExpenseReportService;
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
public class ExpenseReportServiceImpl implements ExpenseReportService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ExpenseReportRepository expenseReportRepository;
    private final CostCenterRepository costCenterRepository;
    private final CurrencyRepository currencyRepository;
    private final UmsClient umsClient;
    private final CurrentUserService currentUserService;
    private final ExpenseReportMapper expenseReportMapper;

    /** Minimum character length for the business purpose free-text field — configurable per FR: "minimum 10 characters (configurable)". */
    @Value("${expense-report.business-purpose.min-length:10}")
    private int businessPurposeMinLength;

    @Override
    public ExpenseReportResponse create(ExpenseReportRequest request) {
        CurrentUser caller = currentUserService.getCurrentUser();
        assertEmployeeActive(caller);
        assertBusinessPurposeLongEnough(request.businessPurpose());

        String fiscalYear = currentFiscalYear();
        assertTitleNotDuplicate(caller.employeeId(), fiscalYear, request.title(), null);

        CostCenter costCenter = findActiveCostCenter(request.costCenterId());
        Currency currency = findActiveCurrency(request.currencyId());

        ExpenseReport entity = expenseReportMapper.toEntity(request);
        entity.setEmployeeId(caller.employeeId());
        entity.setFiscalYear(fiscalYear);
        entity.setReportStatus(ReportStatus.DRAFT);
        entity.setReportNumber(generateReportNumber(fiscalYear));
        entity.setCostCenter(costCenter);
        entity.setCurrency(currency);
        entity.setTotalAmount(java.math.BigDecimal.ZERO);
        entity.setReimbursableAmount(java.math.BigDecimal.ZERO);

        ExpenseReport saved = expenseReportRepository.save(entity);
        log.info("Created Draft expense report {} for employee {}", saved.getReportNumber(), saved.getEmployeeId());
        return toResponse(saved);
    }

    @Override
    public ExpenseReportResponse update(UUID reportId, ExpenseReportRequest request) {
        ExpenseReport entity = findEntity(reportId);
        assertOwnerOrAdmin(entity);
        assertEditable(entity);
        assertBusinessPurposeLongEnough(request.businessPurpose());
        assertTitleNotDuplicate(entity.getEmployeeId(), entity.getFiscalYear(), request.title(), reportId);

        expenseReportMapper.updateEntity(entity, request);
        entity.setCostCenter(findActiveCostCenter(request.costCenterId()));
        entity.setCurrency(findActiveCurrency(request.currencyId()));

        ExpenseReport saved = expenseReportRepository.save(entity);
        log.info("Updated expense report {}", reportId);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseReportResponse getById(UUID reportId) {
        ExpenseReport entity = findEntity(reportId);
        assertViewable(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseReportResponse> getAll() {
        CurrentUser caller = currentUserService.getCurrentUser();
        List<ExpenseReport> reports = isPrivilegedReviewer(caller)
                ? expenseReportRepository.findAll()
                : expenseReportRepository.findByEmployeeId(caller.employeeId());
        return reports.stream().map(this::toResponse).toList();
    }

    @Override
    public void delete(UUID reportId) {
        ExpenseReport entity = findEntity(reportId);
        assertOwnerOrAdmin(entity);
        if (!entity.getReportStatus().isDeletable()) {
            throw new BusinessRuleViolationException(
                    "Expense report cannot be deleted in status " + entity.getReportStatus() + " — only a Draft report may be deleted");
        }
        expenseReportRepository.delete(entity);
        log.info("Deleted Draft expense report {}", reportId);
    }

    private void assertEmployeeActive(CurrentUser caller) {
        UmsUserResponse umsUser = umsClient.getAllUsers().stream()
                .filter(user -> caller.uuid().equals(user.userUuid()))
                .findFirst()
                .orElseThrow(() -> new EmployeeInactiveException(
                        "Your UMS account could not be verified. Please contact HR Support to resolve this before creating an expense report."));

        if (!umsUser.isActive()) {
            throw new EmployeeInactiveException(
                    "Your account is marked Inactive in UMS. Expense reports cannot be created while inactive — please contact HR Support to reactivate your account.");
        }
    }

    private void assertBusinessPurposeLongEnough(String businessPurpose) {
        if (businessPurpose == null || businessPurpose.trim().length() < businessPurposeMinLength) {
            throw new IllegalArgumentException(
                    "businessPurpose must be at least " + businessPurposeMinLength + " characters long");
        }
    }

    private void assertTitleNotDuplicate(String employeeId, String fiscalYear, String title, UUID currentReportId) {
        expenseReportRepository.findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(employeeId, fiscalYear, title)
                .ifPresent(existing -> {
                    if (!existing.getReportId().equals(currentReportId)) {
                        throw new DuplicateResourceException(
                                "An expense report titled '" + title + "' already exists for fiscal year " + fiscalYear);
                    }
                });
    }

    private void assertOwnerOrAdmin(ExpenseReport entity) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (hasRole(caller, RoleConstants.ADMIN)) {
            return;
        }
        if (!entity.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only modify your own expense report");
        }
    }

    private void assertViewable(ExpenseReport entity) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (isPrivilegedReviewer(caller)) {
            return;
        }
        if (!entity.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view your own expense report");
        }
    }

    private void assertEditable(ExpenseReport entity) {
        if (!entity.getReportStatus().isEditable()) {
            throw new BusinessRuleViolationException(
                    "Expense report cannot be edited in status " + entity.getReportStatus());
        }
    }

    private boolean isPrivilegedReviewer(CurrentUser caller) {
        return hasRole(caller, RoleConstants.ADMIN) || hasRole(caller, RoleConstants.FINANCE)
                || hasRole(caller, RoleConstants.MANAGER);
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    private CostCenter findActiveCostCenter(UUID costCenterId) {
        CostCenter costCenter = costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(costCenter.getStatus())) {
            throw new IllegalArgumentException(
                    "Cost center " + costCenter.getCostCenterCode() + " is not Active and cannot be selected");
        }
        return costCenter;
    }

    private Currency findActiveCurrency(UUID currencyId) {
        Currency currency = currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
        if (currency.getStatus() != null && !STATUS_ACTIVE.equalsIgnoreCase(currency.getStatus())) {
            throw new IllegalArgumentException(
                    "Currency " + currency.getCurrencyCode() + " is not Active and cannot be selected");
        }
        return currency;
    }

    private ExpenseReport findEntity(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    /** Calendar year the report is created in — the fiscal period boundary for title-uniqueness scoping. */
    private String currentFiscalYear() {
        return String.valueOf(Year.from(LocalDate.now()).getValue());
    }

    private String generateReportNumber(String fiscalYear) {
        return "EXP-" + fiscalYear + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private ExpenseReportResponse toResponse(ExpenseReport entity) {
        boolean editable = entity.getReportStatus().isEditable();
        boolean deletable = entity.getReportStatus().isDeletable();
        return expenseReportMapper.toResponse(entity, editable, deletable);
    }
}
