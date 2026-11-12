package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.InvoiceHandoffRequest;
import com.expense_management_service.dto.response.InvoiceHandoffEligibleExpenseResponse;
import com.expense_management_service.dto.response.InvoiceHandoffRecordResponse;
import com.expense_management_service.dto.response.InvoiceHandoffSummaryResponse;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.enums.InvoiceHandoffStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.integration.pms.PmsClient;
import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import com.expense_management_service.repository.InvoiceSyncRepository;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.InvoiceHandoffService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class InvoiceHandoffServiceImpl implements InvoiceHandoffService {

    /** {@code InvoiceSync.syncStatus} value this feature writes — a free-text column, so the value is owned here, not an enum. */
    static final String STATUS_HANDED_OFF = "HANDED_OFF";

    /** Start of a re-handoff's remarks, followed by the reason - AR's message carries both. */
    static final String REPROCESS_REMARK_PREFIX = "Reprocessed: ";

    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final InvoiceSyncRepository invoiceSyncRepository;
    private final InvoiceSyncMapper invoiceSyncMapper;
    private final com.expense_management_service.service.ApprovalEventPublisher approvalEventPublisher;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final FinanceVerificationReviewRepository financeVerificationReviewRepository;
    private final PmsClient pmsClient;
    private final ProjectCacheRepository projectCacheRepository;

    /** Same organization base-currency property {@code ExpenseLineItemServiceImpl} uses — {@code baseAmount} is always denominated in it. */
    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvoiceHandoffEligibleExpenseResponse> getEligibleExpenses(
            UUID clientId, UUID projectId, Long pmsProjectId, String projectCode,
            LocalDate startDate, LocalDate endDate, int page, int size) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        Page<ExpenseLineItem> result = expenseLineItemRepository.findEligibleForInvoiceHandoff(
                clientId, projectId, pmsProjectId, StringUtils.hasText(projectCode) ? projectCode.trim() : null,
                startDate, endDate, PageRequest.of(page, size));
        return PageResponse.of(result.map(this::toEligibleExpenseResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public com.expense_management_service.dto.response.BillingPayloadResponse getBillingPayload(UUID lineItemId) {
        ExpenseLineItem lineItem = findLineItem(lineItemId);
        ExpenseReport report = lineItem.getReport();
        if (!Boolean.TRUE.equals(lineItem.getClientBillable()) || report == null
                || (report.getInvoiceHandoffStatus() != InvoiceHandoffStatus.PENDING
                && report.getInvoiceHandoffStatus() != InvoiceHandoffStatus.COMPLETED)) {
            throw new BusinessRuleViolationException(
                    "Line item " + lineItemId + " is not a client-billable expense on an approved report");
        }
        var handedOff = invoiceSyncRepository.findByLineItem_LineItemIdOrderBySyncDateDesc(lineItemId).stream()
                .filter(sync -> STATUS_HANDED_OFF.equals(sync.getSyncStatus()))
                .findFirst();
        return BillingPayloadFactory.build(lineItem, baseCurrencyCode, handedOff.isPresent() ? STATUS_HANDED_OFF : "PENDING",
                handedOff.map(InvoiceSync::getInvoiceReference).orElse(null));
    }

    @Override
    public InvoiceSyncResponse markHandedOff(UUID lineItemId, InvoiceHandoffRequest request) {
        // Locked: a second, concurrent handoff of this line waits here and then sees this one's record.
        ExpenseLineItem lineItem = expenseLineItemRepository.findWithLockByLineItemId(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
        assertEligibleForHandoff(lineItem);
        verifyProjectAgainstPms(lineItem);

        boolean alreadyHandedOff = invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(lineItemId, STATUS_HANDED_OFF);
        boolean reprocess = Boolean.TRUE.equals(request.allowReprocess());
        if (alreadyHandedOff && !reprocess) {
            throw new DuplicateResourceException(
                    "Line item " + lineItemId + " has already been handed off to invoicing. "
                            + "Set allowReprocess=true with a reason to hand it off again.");
        }
        if (alreadyHandedOff && !StringUtils.hasText(request.reason())) {
            throw new BusinessRuleViolationException(
                    "A reason is required to re-hand-off an already-handed-off line item");
        }

        InvoiceSync sync = InvoiceSync.builder()
                .lineItem(lineItem)
                .invoiceReference(request.invoiceReference())
                .syncStatus(STATUS_HANDED_OFF)
                .syncDate(LocalDateTime.now())
                .retryCount(0)
                .remarks(alreadyHandedOff ? REPROCESS_REMARK_PREFIX + request.reason() : null)
                .build();

        // Recorded only - nothing is pushed to AR yet; AR reads handoffs from /handed-off until the AR integration is built.
        InvoiceSync saved = invoiceSyncRepository.save(sync);
        InvoiceSyncResponse response = invoiceSyncMapper.toResponse(saved);
        completeReportHandoffIfAllLinesHandedOff(lineItem.getReport());
        approvalEventPublisher.publish("EXPENSE_BILLABLE_HANDED_OFF", lineItem.getReport().getReportId(),
                "lineItemId=" + lineItemId + " payloadVersion=" + BillingPayloadFactory.PAYLOAD_VERSION);
        return response;
    }

    /**
     * The same rule the handoff queue lists by ({@code ExpenseLineItemRepository.findEligibleForInvoiceHandoff}),
     * applied to the one line being handed off, so a direct call can't hand off what the queue would never show:
     * client-billable with a client to bill, on an APPROVED report routed to invoicing, and verified by Finance in
     * the report's current submission cycle. Only the "not yet handed off" part is left to the reprocess rules.
     */
    private void assertEligibleForHandoff(ExpenseLineItem lineItem) {
        ExpenseReport report = lineItem.getReport();
        String line = "Line item " + lineItem.getLineItemId();
        if (!Boolean.TRUE.equals(lineItem.getClientBillable())) {
            throw new BusinessRuleViolationException(line + " is not client-billable, so it can't be handed off to invoicing");
        }
        if (lineItem.getResolvedClientId() == null) {
            throw new BusinessRuleViolationException(line + " has no client to bill. Correct the expense before handing it off.");
        }
        if (report == null || report.getReportStatus() != ReportStatus.APPROVED) {
            throw new BusinessRuleViolationException(line + " can't be handed off until its report is approved"
                    + (report != null ? " (report " + report.getReportNumber() + " is " + report.getReportStatus() + ")" : ""));
        }
        if (report.getInvoiceHandoffStatus() != InvoiceHandoffStatus.PENDING
                && report.getInvoiceHandoffStatus() != InvoiceHandoffStatus.COMPLETED) {
            throw new BusinessRuleViolationException("Report " + report.getReportNumber() + " was not routed to invoicing");
        }
        int cycle = approvalWorkflowService.getCurrentSubmissionCycle(report.getReportId());
        if (!financeVerificationReviewRepository.existsByLineItem_LineItemIdAndStatusAndLevelInstance_SubmissionCycle(
                lineItem.getLineItemId(), FinanceVerificationStatus.VERIFIED, cycle)) {
            throw new BusinessRuleViolationException(line + " hasn't been verified by Finance on report "
                    + report.getReportNumber() + "'s current submission, so it can't be handed off yet");
        }
    }

    /**
     * Confirms the line's project against PMS at the moment it goes to AR, not only when the employee saved it:
     * the project must still exist in PMS and still belong to the client being billed. PMS is the source of
     * truth for the project code and name, so the cached copy is refreshed from it before the handoff is
     * recorded and AR reads them. A line billed to a client without a project has nothing to check.
     */
    private void verifyProjectAgainstPms(ExpenseLineItem lineItem) {
        ProjectCache project = lineItem.getProject();
        if (project == null) {
            return;
        }
        Long pmsProjectId = project.getPmsProjectId();
        if (pmsProjectId == null) {
            throw new BusinessRuleViolationException("Project " + project.getProjectCode()
                    + " has no PMS reference, so its expenses can't be handed off to invoicing");
        }
        PmsProjectDetailResponse pmsProject = pmsClient.getProject(pmsProjectId)
                .orElseThrow(() -> new BusinessRuleViolationException("Project " + project.getProjectCode()
                        + " (PMS id " + pmsProjectId + ") no longer exists in PMS. Correct the expense's project before handing it off."));
        if (pmsProject.clientId() == null || !pmsProject.clientId().equals(lineItem.getResolvedClientId())) {
            throw new BusinessRuleViolationException("In PMS, project " + project.getProjectCode()
                    + " no longer belongs to " + (lineItem.getResolvedClientName() != null ? lineItem.getResolvedClientName() : "the client")
                    + " this expense bills. Correct the expense before handing it off.");
        }
        if (pmsProject.projectKey() != null) {
            project.setProjectCode(pmsProject.projectKey());
        }
        if (pmsProject.name() != null) {
            project.setProjectName(pmsProject.name());
        }
        project.setStatus(pmsProject.status());
        project.setSyncedAt(LocalDateTime.now());
        projectCacheRepository.save(project);
    }

    /**
     * Moves the report's invoice track to COMPLETED once every client-billable line item on it
     * has an active HANDED_OFF record. Only touches {@code invoiceHandoffStatus} — the AP payment
     * track ({@code paymentRoutingStatus}) is independent and never read or written here.
     */
    private void completeReportHandoffIfAllLinesHandedOff(ExpenseReport report) {
        if (report == null || report.getExpenseLineItems() == null
                || report.getInvoiceHandoffStatus() == InvoiceHandoffStatus.COMPLETED) {
            return;
        }
        boolean allHandedOff = report.getExpenseLineItems().stream()
                .filter(li -> Boolean.TRUE.equals(li.getClientBillable()))
                .allMatch(li -> invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(li.getLineItemId(), STATUS_HANDED_OFF));
        if (allHandedOff) {
            report.setInvoiceHandoffStatus(InvoiceHandoffStatus.COMPLETED);
            expenseReportRepository.save(report);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceSyncResponse> getHandoffHistory(UUID lineItemId) {
        return invoiceSyncRepository.findByLineItem_LineItemIdOrderBySyncDateDesc(lineItemId).stream()
                .map(invoiceSyncMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvoiceHandoffRecordResponse> getHandedOff(
            UUID clientId, Long pmsProjectId, String projectCode,
            LocalDate handedOffFrom, LocalDate handedOffTo, int page, int size) {
        if (handedOffFrom != null && handedOffTo != null && handedOffFrom.isAfter(handedOffTo)) {
            throw new IllegalArgumentException("handedOffFrom must be on or before handedOffTo");
        }
        return PageResponse.of(invoiceSyncRepository
                .findHandedOff(STATUS_HANDED_OFF, clientId, pmsProjectId,
                        StringUtils.hasText(projectCode) ? projectCode.trim() : null,
                        handedOffFrom != null ? handedOffFrom.atStartOfDay() : null,
                        handedOffTo != null ? handedOffTo.plusDays(1).atStartOfDay() : null,
                        PageRequest.of(page, size))
                .map(this::toRecordResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvoiceHandoffRecordResponse> getHandedOffByPmsProject(Long pmsProjectId, int page, int size) {
        PageResponse<InvoiceHandoffRecordResponse> result = getHandedOff(null, pmsProjectId, null, null, null, page, size);
        if (result.content().isEmpty()) {
            throw new ResourceNotFoundException(result.totalElements() == 0
                    ? "No handed-off expenses found for PMS project " + pmsProjectId
                    : "Page " + page + " is beyond the last page (" + (result.totalPages() - 1)
                            + ") of handed-off expenses for PMS project " + pmsProjectId);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvoiceHandoffRecordResponse> getHandedOffByClient(UUID clientId, int page, int size) {
        PageResponse<InvoiceHandoffRecordResponse> result = getHandedOff(clientId, null, null, null, null, page, size);
        if (result.content().isEmpty()) {
            throw new ResourceNotFoundException(result.totalElements() == 0
                    ? "No handed-off expenses found for client " + clientId
                    : "Page " + page + " is beyond the last page (" + (result.totalPages() - 1)
                            + ") of handed-off expenses for client " + clientId);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceHandoffSummaryResponse getSummary() {
        // Same eligibility rule as the queue itself, unpaged, so the numbers can never disagree with it.
        List<ExpenseLineItem> ready = expenseLineItemRepository
                .findEligibleForInvoiceHandoff(null, null, null, null, null, null, Pageable.unpaged())
                .getContent();
        BigDecimal readyAmount = ready.stream()
                .map(ExpenseLineItem::getBaseAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long readyProjects = ready.stream()
                .map(l -> l.getProject() != null ? l.getProject().getProjectId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long readyClients = ready.stream()
                .map(ExpenseLineItem::getResolvedClientId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return new InvoiceHandoffSummaryResponse(ready.size(), readyAmount, readyProjects,
                invoiceSyncRepository.countBySyncStatus(STATUS_HANDED_OFF), baseCurrencyCode, readyClients);
    }

    private InvoiceHandoffRecordResponse toRecordResponse(InvoiceSync sync) {
        ExpenseLineItem l = sync.getLineItem();
        var category = l.getCategory();
        var project = l.getProject();
        return new InvoiceHandoffRecordResponse(
                sync.getSyncId(),
                l.getLineItemId(),
                l.getReport().getReportId(),
                l.getReport().getReportNumber(),
                l.getReport().getEmployeeId(),
                l.getExpenseDate(),
                category != null ? category.getCategoryName() : null,
                l.getDescription(),
                l.getBaseAmount(),
                baseCurrencyCode,
                BillingPayloadFactory.costBasis(l),
                l.getBaseRecoverableTaxAmount(),
                l.getTaxCode(),
                project != null ? project.getPmsProjectId() : null,
                project != null ? project.getProjectCode() : null,
                project != null ? project.getProjectName() : null,
                l.getResolvedClientId(),
                l.getResolvedClientName(),
                sync.getInvoiceReference(),
                sync.getSyncDate(),
                sync.getRemarks()
        );
    }

    private InvoiceHandoffEligibleExpenseResponse toEligibleExpenseResponse(ExpenseLineItem l) {
        var category = l.getCategory();
        var project = l.getProject();
        return new InvoiceHandoffEligibleExpenseResponse(
                l.getLineItemId(),
                l.getReport().getReportId(),
                l.getReport().getReportNumber(),
                l.getReport().getEmployeeId(),
                l.getExpenseDate(),
                category != null ? category.getCategoryName() : null,
                l.getDescription(),
                l.getAmount(),
                l.getCurrency() != null ? l.getCurrency().getCurrencyCode() : null,
                l.getBaseAmount(),
                baseCurrencyCode,
                l.getTaxAmount(),
                l.getNetAmount(),
                BillingPayloadFactory.costBasis(l),
                l.getBaseRecoverableTaxAmount(),
                l.getTaxCode(),
                project != null ? project.getProjectId() : null,
                project != null ? project.getPmsProjectId() : null,
                project != null ? project.getProjectCode() : null,
                project != null ? project.getProjectName() : null,
                l.getResolvedClientId(),
                l.getResolvedClientName(),
                l.getReceipts() != null ? l.getReceipts().size() : 0
        );
    }

    private ExpenseLineItem findLineItem(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }
}
