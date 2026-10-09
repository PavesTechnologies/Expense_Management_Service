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
import com.expense_management_service.enums.InvoiceHandoffStatus;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.InvoiceSyncRepository;
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
    private static final String STATUS_HANDED_OFF = "HANDED_OFF";

    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final InvoiceSyncRepository invoiceSyncRepository;
    private final InvoiceSyncMapper invoiceSyncMapper;
    private final com.expense_management_service.service.ApprovalEventPublisher approvalEventPublisher;

    /** Same organization base-currency property {@code ExpenseLineItemServiceImpl} uses — {@code baseAmount} is always denominated in it. */
    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvoiceHandoffEligibleExpenseResponse> getEligibleExpenses(
            UUID clientId, UUID projectId, LocalDate startDate, LocalDate endDate, int page, int size) {
        Page<ExpenseLineItem> result = expenseLineItemRepository.findEligibleForInvoiceHandoff(
                clientId, projectId, startDate, endDate, PageRequest.of(page, size));
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
        ExpenseLineItem lineItem = findLineItem(lineItemId);

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
                .remarks(alreadyHandedOff ? "Reprocessed: " + request.reason() : null)
                .build();

        InvoiceSyncResponse response = invoiceSyncMapper.toResponse(invoiceSyncRepository.save(sync));
        completeReportHandoffIfAllLinesHandedOff(lineItem.getReport());
        approvalEventPublisher.publish("EXPENSE_BILLABLE_HANDED_OFF", lineItem.getReport().getReportId(),
                "lineItemId=" + lineItemId + " payloadVersion=" + BillingPayloadFactory.PAYLOAD_VERSION);
        return response;
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
    public PageResponse<InvoiceHandoffRecordResponse> getHandedOff(int page, int size) {
        return PageResponse.of(invoiceSyncRepository
                .findBySyncStatusOrderBySyncDateDesc(STATUS_HANDED_OFF, PageRequest.of(page, size))
                .map(this::toRecordResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceHandoffSummaryResponse getSummary() {
        // Same eligibility rule as the queue itself, unpaged, so the numbers can never disagree with it.
        List<ExpenseLineItem> ready = expenseLineItemRepository
                .findEligibleForInvoiceHandoff(null, null, null, null, Pageable.unpaged())
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
        return new InvoiceHandoffSummaryResponse(ready.size(), readyAmount, readyProjects,
                invoiceSyncRepository.countBySyncStatus(STATUS_HANDED_OFF), baseCurrencyCode);
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
                project != null ? project.getProjectCode() : null,
                project != null ? project.getProjectName() : null,
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
