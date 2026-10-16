package com.expense_management_service.service;

import com.expense_management_service.dto.request.InvoiceHandoffRequest;
import com.expense_management_service.dto.response.InvoiceHandoffEligibleExpenseResponse;
import com.expense_management_service.dto.response.InvoiceHandoffRecordResponse;
import com.expense_management_service.dto.response.InvoiceHandoffSummaryResponse;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.dto.response.PageResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Client invoice handoff (Epic 8) — separate from AP reimbursement. Reuses {@code InvoiceSync}
 * as the handoff record rather than a new entity; the AP payment flow ({@code
 * ApPaymentService}/{@code ExpenseReport.paymentRoutingStatus}) is entirely independent of
 * this and is never read or written here.
 */
public interface InvoiceHandoffService {

    /**
     * All filters are optional and combined with AND; both date bounds are inclusive on the
     * expense date. External callers (AR) match on {@code clientId} - RMS's client UUID. Project
     * filters still work but only match line items that have a project, which is optional;
     * {@code projectId} is XMS's internal project UUID.
     */
    PageResponse<InvoiceHandoffEligibleExpenseResponse> getEligibleExpenses(
            UUID clientId, UUID projectId, Long pmsProjectId, String projectCode,
            LocalDate startDate, LocalDate endDate, int page, int size);

    /**
     * AR's authoritative billing payload for one client-billable line on an approved report
     * (BR-TAX-015 / BR-TAX-023). Safe to call repeatedly - it returns current state.
     */
    com.expense_management_service.dto.response.BillingPayloadResponse getBillingPayload(UUID lineItemId);

    InvoiceSyncResponse markHandedOff(UUID lineItemId, InvoiceHandoffRequest request);

    List<InvoiceSyncResponse> getHandoffHistory(UUID lineItemId);

    /**
     * Completed handoffs, newest first - the invoice team's "Handed off" tab and AR's pull of
     * what to invoice. All filters are optional and combined with AND; both handed-off date
     * bounds are inclusive. A reprocessed line item appears once per handoff.
     */
    PageResponse<InvoiceHandoffRecordResponse> getHandedOff(
            UUID clientId, Long pmsProjectId, String projectCode,
            LocalDate handedOffFrom, LocalDate handedOffTo, int page, int size);

    /**
     * Completed handoffs for one PMS project, newest first - same records as {@link #getHandedOff}.
     * Throws {@code ResourceNotFoundException} when the requested page is empty, whether the
     * project has no handoffs at all or the page is past the last one.
     *
     * Superseded for AR by {@link #getHandedOffByClient} (AR matches on client); line items
     * billed to a client without a project never appear here.
     */
    PageResponse<InvoiceHandoffRecordResponse> getHandedOffByPmsProject(Long pmsProjectId, int page, int size);

    /**
     * Completed handoffs for one RMS client, newest first - same records as {@link #getHandedOff}.
     * Throws {@code ResourceNotFoundException} when the requested page is empty, whether the
     * client has no handoffs at all or the page is past the last one.
     */
    PageResponse<InvoiceHandoffRecordResponse> getHandedOffByClient(UUID clientId, int page, int size);

    InvoiceHandoffSummaryResponse getSummary();
}
