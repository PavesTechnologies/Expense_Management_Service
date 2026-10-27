package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ClientResponse;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.integration.pms.PmsClient;
import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.mapper.ExpenseLineItemMapper;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.ExchangeRateService;
import com.expense_management_service.service.ClientService;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.service.ExpenseSplitService;
import com.expense_management_service.service.TaxSnapshotService;
import com.expense_management_service.service.PolicyEvaluator;
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
    /** Set on a line item whenever its recomputed policy violations include a BLOCK-enforced one; see {@link #refreshPolicyViolations}. */
    private static final String STATUS_BLOCKED = "BLOCKED";

    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final CurrencyRepository currencyRepository;
    private final CostCenterRepository costCenterRepository;
    private final ProjectCacheRepository projectCacheRepository;
    private final ExchangeRateService exchangeRateService;
    private final CurrentUserService currentUserService;
    private final PmsClient pmsClient;
    private final ClientService clientService;
    private final ExpenseLineItemMapper expenseLineItemMapper;
    private final PolicyEvaluator policyEvaluator;
    private final PolicyViolationRepository policyViolationRepository;
    private final PolicyViolationMapper policyViolationMapper;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final DelegationService delegationService;
    private final ExpenseSplitService expenseSplitService;
    private final TaxSnapshotService taxSnapshotService;
    private final SubmittedLineItemRemover submittedLineItemRemover;

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
        assertExpenseDateWithinReport(report, request.expenseDate());
        assertTaxAmountValid(request.amount(), request.taxAmount());

        ExpenseLineItem entity = expenseLineItemMapper.toEntity(request);
        entity.setReport(report);
        entity.setCategory(category);
        entity.setLineStatus(STATUS_ACTIVE);
        applyRelations(entity, request);
        applyCurrencyConversion(entity);
        applyTaxSnapshot(entity, request, null);

        ExpenseLineItem saved = expenseLineItemRepository.save(entity);
        refreshPolicyViolations(saved);
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
        assertExpenseDateWithinReport(report, request.expenseDate());
        assertTaxAmountValid(request.amount(), request.taxAmount());

        var previousTax = taxSnapshotService.describe(entity);
        boolean keepFinanceTax = keepsFinanceAdjustedTax(entity, request, category);
        BigDecimal financeTaxAmount = entity.getTaxAmount();
        expenseLineItemMapper.updateEntity(entity, request);
        entity.setCategory(category);
        applyRelations(entity, request);
        applyCurrencyConversion(entity);
        if (keepFinanceTax) {
            // Finance corrected this line's tax and the employee changed nothing it depends on:
            // recalculating from the tax code would silently undo Finance's figures.
            entity.setTaxAmount(financeTaxAmount);
        } else {
            applyTaxSnapshot(entity, request, previousTax);
        }
        if (report.getSubmittedAt() != null) {
            // Edited after submission (i.e. during a correction): already-given reviews of this line
            // are reopened when the report is resubmitted.
            entity.setRevisedAt(LocalDateTime.now());
        }

        ExpenseLineItem saved = expenseLineItemRepository.save(entity);
        // Splits are allocated against baseAmount; an amount or currency edit would otherwise leave
        // them summing to the old total (and consuming the wrong cost-center budgets).
        expenseSplitService.rebalanceSplitsForLineItem(saved);
        refreshPolicyViolations(saved);
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
        if (report.getSubmittedAt() != null) {
            // Removed during a correction: clear the review/budget rows that reference it, keeping
            // their outcome in the audit log.
            submittedLineItemRemover.prepareForDeletion(entity, currentUserService.getCurrentUser().employeeId());
        }
        expenseLineItemRepository.delete(entity);
        recalculateReportTotal(report);
        log.info("Deleted line item {} from expense report {}", lineItemId, reportId);
    }

    /**
     * An item must be dated inside the report's trip dates, and not in the future. Reports without trip dates
     * have no range to check against.
     */
    private void assertExpenseDateWithinReport(ExpenseReport report, LocalDate expenseDate) {
        assertExpenseDateValid(expenseDate);
        LocalDate start = report.getTripStartDate();
        LocalDate end = report.getTripEndDate();
        if (start == null || end == null) {
            return;
        }
        if (expenseDate.isBefore(start) || expenseDate.isAfter(end)) {
            throw new IllegalArgumentException("Expense date " + expenseDate + " must fall within the report's trip dates ("
                    + start + " to " + end + ").");
        }
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

        if (Boolean.TRUE.equals(request.clientBillable())) {
            resolveClientBillable(entity, request.projectId(), request.clientId());
        } else {
            // Internal expense: preserve the original lenient behavior exactly — project is
            // optional, no PMS/RMS calls, no unnecessary requirements imposed.
            UUID projectId = request.projectId();
            entity.setProject(projectId == null ? null : findProject(projectId));
            entity.setResolvedClientId(null);
            entity.setResolvedClientName(null);
        }
    }

    /**
     * Client resolution for a client-billable line item — runs on every {@code create}/{@code
     * update}, so a correction/resubmission re-validates exactly like a first submission does.
     * At least one of {@code clientId}/{@code projectId} is required; project is optional when a
     * client is selected.
     * <ul>
     *   <li>{@code projectId} given: validated live against PMS as before
     *       ({@link #resolveClientBillableProject}).</li>
     *   <li>{@code clientId} given: must be an ACTIVE RMS client; if a project is also given, it
     *       must belong to that same client in PMS.</li>
     *   <li>Only {@code projectId} given: the client is PMS's {@code Project.clientId} (the
     *       pre-client-selection behaviour, unchanged).</li>
     * </ul>
     * Throws {@link BusinessRuleViolationException} (422) for every failure mode rather than
     * silently defaulting a project/client.
     */
    private void resolveClientBillable(ExpenseLineItem entity, UUID projectId, UUID clientId) {
        if (projectId == null && clientId == null) {
            throw new BusinessRuleViolationException(
                    "A client must be selected for a client-billable expense line item");
        }

        UUID resolvedClientId = null;
        String resolvedClientName = null;
        if (projectId != null) {
            ProjectCache project = resolveClientBillableProject(projectId);
            if (clientId != null && !clientId.equals(project.getClientId())) {
                throw new BusinessRuleViolationException(
                        "Project " + project.getProjectCode() + " does not belong to the selected client");
            }
            entity.setProject(project);
            resolvedClientId = project.getClientId();
            resolvedClientName = project.getClientName();
        } else {
            entity.setProject(null);
        }

        if (clientId != null) {
            ClientResponse client = findActiveRmsClient(clientId);
            resolvedClientId = client.clientId();
            resolvedClientName = client.clientName();
        }

        entity.setResolvedClientId(resolvedClientId);
        entity.setResolvedClientName(resolvedClientName);
    }

    /**
     * The selected client from RMS's active list — the same list {@code GET /xms/clients} serves,
     * with the same error mapping (RMS 401/403 → 403, other RMS failures → 502).
     */
    private ClientResponse findActiveRmsClient(UUID clientId) {
        return clientService.getActiveClients().stream()
                .filter(c -> clientId.equals(c.clientId()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "The selected client was not found in RMS or is no longer active"));
    }

    /**
     * Backend-enforced project assignment for a client-billable line item. Everything here is
     * re-fetched live from PMS; the project's client is PMS's {@code Project.clientId}. A
     * client-billable expense whose project has no client in PMS must never be allowed to save.
     */
    private ProjectCache resolveClientBillableProject(UUID projectId) {
        ProjectCache project = findProject(projectId);

        Long pmsProjectId = project.getPmsProjectId();
        if (pmsProjectId == null) {
            throw new BusinessRuleViolationException(
                    "Project " + project.getProjectCode()
                            + " has no PMS reference and cannot be used for a client-billable expense");
        }

        Long callerUmsUserId = currentUserService.getUmsUserId();
        boolean assigned = pmsClient.getMyActiveProjects(callerUmsUserId).stream()
                .anyMatch(assignedProject -> pmsProjectId.equals(assignedProject.id()));
        if (!assigned) {
            throw new BusinessRuleViolationException(
                    "You are not currently assigned to project " + project.getProjectCode()
                            + " in PMS, or it is no longer active");
        }

        PmsProjectDetailResponse pmsProject = pmsClient.getProject(pmsProjectId)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Project " + project.getProjectCode() + " could not be found in PMS"));
        if (pmsProject.clientId() == null) {
            throw new BusinessRuleViolationException(
                    "Project " + project.getProjectCode() + " has no client associated with it in PMS");
        }

        // The project's client is taken as PMS reports it — clientId only, no name/status. RMS is
        // consulted only when the employee also selected a client (see resolveClientBillable).

        // Refresh the cache row with the latest PMS data — never treat what was cached before
        // this call as authoritative for a submission decision.
        if (pmsProject.name() != null) {
            project.setProjectName(pmsProject.name());
        }
        if (pmsProject.projectKey() != null) {
            project.setProjectCode(pmsProject.projectKey());
        }
        project.setStatus(pmsProject.status());
        project.setClientId(pmsProject.clientId());
        project.setSyncedAt(LocalDateTime.now());
        projectCacheRepository.save(project);
        return project;
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
     * Re-runs {@link PolicyEvaluator} against the saved line item and replaces its stored
     * violations, carrying forward any existing justification whose (ruleType, policyRule) still
     * matches a recomputed violation — so a trivial edit doesn't silently erase an employee's
     * explanation (or an approver's recorded exception). Wrapped defensively: {@link PolicyEvaluator} already promises never to throw,
     * but a policy failure here must never fail the line-item save regardless, mirroring
     * {@link #applyCurrencyConversion}'s fail-open posture in this same class.
     * <p>
     * Also applies the BLOCK enforcement step: if any recomputed violation is
     * {@code enforcementType == BLOCK} and not yet justified, {@code lineItem.lineStatus} is set to
     * {@link #STATUS_BLOCKED}; otherwise it reverts to {@link #STATUS_ACTIVE} (once the employee
     * explains it, or edits the line back within policy). No exception is thrown for a BLOCK violation — the line
     * item and its violations are still persisted, just flagged, so the existing policy-warning
     * endpoint can surface them. {@code lineItem} is already a managed entity by this point (just
     * persisted/merged by the caller), so mutating its status here is enough — Hibernate's dirty
     * checking flushes it at transaction commit without a second explicit save.
     */
    private void refreshPolicyViolations(ExpenseLineItem lineItem) {
        try {
            List<PolicyViolation> existing = policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId());
            List<PolicyViolation> recomputed = policyEvaluator.evaluate(lineItem);

            for (PolicyViolation violation : recomputed) {
                existing.stream()
                        .filter(old -> sameRule(old, violation))
                        .findFirst()
                        .ifPresent(old -> {
                            violation.setJustification(old.getJustification());
                            violation.setJustifiedAt(old.getJustifiedAt());
                            violation.setJustifiedBy(old.getJustifiedBy());
                            violation.setApproverJustification(old.getApproverJustification());
                            violation.setApproverJustifiedBy(old.getApproverJustifiedBy());
                            violation.setApproverJustifiedAt(old.getApproverJustifiedAt());
                        });
            }

            policyViolationRepository.deleteAll(existing);
            policyViolationRepository.saveAll(recomputed);

            lineItem.setLineStatus(PolicyViolation.blocksLineItem(recomputed) ? STATUS_BLOCKED : STATUS_ACTIVE);
        } catch (Exception ex) {
            log.warn("Policy evaluation failed for line item {} - continuing without policy warnings",
                    lineItem.getLineItemId(), ex);
        }
    }

    private boolean sameRule(PolicyViolation existing, PolicyViolation recomputed) {
        if (existing.getRuleType() != recomputed.getRuleType()) {
            return false;
        }
        UUID existingRuleId = existing.getPolicyRule() != null ? existing.getPolicyRule().getPolicyId() : null;
        UUID recomputedRuleId = recomputed.getPolicyRule() != null ? recomputed.getPolicyRule().getPolicyId() : null;
        return Objects.equals(existingRuleId, recomputedRuleId);
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
    /**
     * Server-side tax (BR-TAX-001): the request's taxAmount is what the employee entered and is
     * compared with the category's (or the selected) tax code; see TaxSnapshotService.
     */
    /**
     * A Finance-adjusted tax stays as Finance set it unless the employee changes something the tax
     * depends on: amount, currency, date, category, an explicit tax code, or an entered tax amount
     * that differs from Finance's.
     */
    private static boolean keepsFinanceAdjustedTax(ExpenseLineItem entity, ExpenseLineItemRequest request, ExpenseCategory category) {
        if (entity.getTaxSource() != com.expense_management_service.enums.TaxSource.FINANCE_ADJUSTED) {
            return false;
        }
        boolean sameAmount = entity.getAmount() != null && request.amount() != null && entity.getAmount().compareTo(request.amount()) == 0;
        boolean sameCurrency = entity.getCurrency() != null && Objects.equals(entity.getCurrency().getCurrencyId(), request.currencyId());
        boolean sameDate = Objects.equals(entity.getExpenseDate(), request.expenseDate());
        boolean sameCategory = entity.getCategory() != null && Objects.equals(entity.getCategory().getCategoryId(), category.getCategoryId());
        boolean sameTaxCode = request.taxCodeId() == null || Objects.equals(request.taxCodeId(), entity.getTaxCodeId());
        boolean sameTax = request.taxAmount() == null
                || (entity.getTaxAmount() != null && entity.getTaxAmount().compareTo(request.taxAmount()) == 0);
        return sameAmount && sameCurrency && sameDate && sameCategory && sameTaxCode && sameTax;
    }

    private void applyTaxSnapshot(ExpenseLineItem entity, ExpenseLineItemRequest request, java.util.Map<String, Object> previousTax) {
        Currency baseCurrency = findOrganizationBaseCurrency();
        int baseScale = baseCurrency.getDecimalPlaces() != null ? baseCurrency.getDecimalPlaces() : 2;
        taxSnapshotService.applySnapshot(entity, request.taxCodeId(), request.taxAmount(), request.taxOverrideReason(),
                previousTax, baseScale);
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
        // Gross less cash advance adjustments (BR-TAX-013). Policy-based non-reimbursable portions
        // (e.g. personal-use exclusions) are a later Policy Management concern.
        report.setReimbursableAmount(ReimbursementCalculator.reimbursable(total, report.getCashAdvanceAdjustments()));
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
                || hasRole(caller, RoleConstants.MANAGER) || hasRole(caller, RoleConstants.FINANCE_EXECUTIVE)
                || hasRole(caller, RoleConstants.AP_EXECUTIVE);
        if (privileged) {
            return;
        }
        if (isAssignedApproverOrDelegate(report, caller.employeeId())) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view line items on your own expense report");
        }
    }

    private boolean isAssignedApproverOrDelegate(ExpenseReport report, String employeeId) {
        if (employeeId == null || report == null || report.getReportId() == null || approvalAssignmentRepository == null) {
            return false;
        }
        return approvalAssignmentRepository.findByLevelInstance_Report_ReportId(report.getReportId())
                .stream()
                .anyMatch(a -> delegationService == null
                        ? employeeId.equals(a.getApproverId())
                        : delegationService.canAct(employeeId, a.getApproverId()));
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
        var warnings = policyViolationRepository.findByLineItem_LineItemId(entity.getLineItemId()).stream()
                .map(policyViolationMapper::toResponse)
                .toList();
        return expenseLineItemMapper.toResponse(entity, categoryActive, baseCurrencyCode, warnings);
    }
}
