package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.InvoiceHandoffRequest;
import com.expense_management_service.dto.response.InvoiceHandoffEligibleExpenseResponse;
import com.expense_management_service.dto.response.InvoiceHandoffRecordResponse;
import com.expense_management_service.dto.response.InvoiceHandoffSummaryResponse;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.service.ArBillingDeliveryService;
import com.expense_management_service.service.InvoiceHandoffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Client invoice handoff queue (Epic 8) — lets the invoice team retrieve finance-verified,
 * client-billable expenses and mark them handed off. Deliberately separate from {@code
 * InvoiceSyncController} (generic ERP invoice-reference bookkeeping, any status) and entirely
 * independent of AP reimbursement ({@code ApPaymentController}) — reuses the same underlying
 * {@code InvoiceSync} record, but this controller owns the eligibility query and the
 * duplicate-handoff guard that generic CRUD doesn't provide.
 * <p>
 * Reuses the existing {@code FINANCE_EXECUTIVE} role rather than introducing a new
 * "invoice team" role — adjustable if the business wants a dedicated role later.
 */
@RestController
@RequestMapping("/xms/finance/invoice-handoff-queue")
@RequiredArgsConstructor
@PreAuthorize("hasRole('FINANCE_EXECUTIVE')")
public class InvoiceHandoffQueueController {

    private final InvoiceHandoffService invoiceHandoffService;
    private final ArBillingDeliveryService arBillingDeliveryService;

    /**
     * Also callable by the AR service account, which filters by {@code clientId} (RMS's client
     * UUID) + billing period. Project filters remain for compatibility but only match line items
     * that have a project, which is optional on a client-billable expense; {@code projectId} is
     * XMS-internal.
     */
    @GetMapping("/eligible-expenses")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','AR_SERVICE')")
    public ApiResponse<PageResponse<InvoiceHandoffEligibleExpenseResponse>> getEligibleExpenses(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Long pmsProjectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(invoiceHandoffService.getEligibleExpenses(
                clientId, projectId, pmsProjectId, projectCode, startDate, endDate, page, Math.min(size, 100)));
    }

    @PostMapping("/{lineItemId}/handoff")
    public ApiResponse<InvoiceSyncResponse> markHandedOff(@PathVariable UUID lineItemId,
                                                           @Valid @RequestBody InvoiceHandoffRequest request) {
        return ApiResponse.success("Expense handed off to invoicing", invoiceHandoffService.markHandedOff(lineItemId, request));
    }

    /**
     * Resends a handoff that hasn't reached AR's queue - FAILED after its retries, still PENDING, or recorded
     * before delivery to AR existed. 422 once it has reached AR; to bill it again, hand the line off again.
     * The response carries the attempt's outcome: SENT, or FAILED with the error.
     */
    @PostMapping("/handoffs/{syncId}/redeliver")
    public ApiResponse<InvoiceSyncResponse> redeliverToAr(@PathVariable UUID syncId) {
        return ApiResponse.success("Handoff resent to AR", arBillingDeliveryService.redeliver(syncId));
    }

    @GetMapping("/summary")
    public ApiResponse<InvoiceHandoffSummaryResponse> getSummary() {
        return ApiResponse.success(invoiceHandoffService.getSummary());
    }

    /**
     * Also callable by the AR service account to pull what the invoice team has handed off,
     * filtered by {@code clientId} and/or handed-off date. {@code pmsProjectId}/{@code
     * projectCode} remain for compatibility; they skip line items billed without a project.
     */
    @GetMapping("/handed-off")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','AR_SERVICE')")
    public ApiResponse<PageResponse<InvoiceHandoffRecordResponse>> getHandedOff(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Long pmsProjectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) LocalDate handedOffFrom,
            @RequestParam(required = false) LocalDate handedOffTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(invoiceHandoffService.getHandedOff(
                clientId, pmsProjectId, projectCode, handedOffFrom, handedOffTo, page, Math.min(size, 100)));
    }

    /**
     * Same records as {@code /handed-off}, for one RMS client ({@code clientId} required) — the
     * key AR invoices by. 404 when the requested page is empty; 400 when {@code clientId} is
     * missing or not a UUID. Open to any authenticated caller, like {@code /handed-off/by-project}.
     */
    @GetMapping("/handed-off/by-client")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<InvoiceHandoffRecordResponse>> getHandedOffByClient(
            @RequestParam UUID clientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(invoiceHandoffService.getHandedOffByClient(clientId, page, Math.min(size, 100)));
    }

    /**
     * Same records as {@code /handed-off}, for one PMS project ({@code pmsProjectId} required).
     * 404 when the requested page is empty; 400 when {@code pmsProjectId} is missing or not numeric.
     * Open to any authenticated caller - no specific role required.
     *
     * @deprecated AR matches on client: use {@code /handed-off/by-client}. Kept until AR has
     * switched; line items billed to a client without a project never appear here.
     */
    @Deprecated
    @GetMapping("/handed-off/by-project")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<InvoiceHandoffRecordResponse>> getHandedOffByPmsProject(
            @RequestParam Long pmsProjectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(invoiceHandoffService.getHandedOffByPmsProject(pmsProjectId, page, Math.min(size, 100)));
    }

    /** AR's authoritative billing payload (v2), the same one AR's queue message carries. Also callable by the AR service account. */
    @GetMapping("/{lineItemId}/billing-payload")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','AR_SERVICE')")
    public ApiResponse<com.expense_management_service.dto.response.BillingPayloadResponse> getBillingPayload(@PathVariable UUID lineItemId) {
        return ApiResponse.success(invoiceHandoffService.getBillingPayload(lineItemId));
    }

    @GetMapping("/{lineItemId}/history")
    public ApiResponse<List<InvoiceSyncResponse>> getHandoffHistory(@PathVariable UUID lineItemId) {
        return ApiResponse.success(invoiceHandoffService.getHandoffHistory(lineItemId));
    }
}
