package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.InvoiceHandoffRequest;
import com.expense_management_service.dto.response.InvoiceHandoffEligibleExpenseResponse;
import com.expense_management_service.dto.response.InvoiceHandoffRecordResponse;
import com.expense_management_service.dto.response.InvoiceHandoffSummaryResponse;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.dto.response.PageResponse;
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

    @GetMapping("/eligible-expenses")
    public ApiResponse<PageResponse<InvoiceHandoffEligibleExpenseResponse>> getEligibleExpenses(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                invoiceHandoffService.getEligibleExpenses(clientId, projectId, startDate, endDate, page, size));
    }

    @PostMapping("/{lineItemId}/handoff")
    public ApiResponse<InvoiceSyncResponse> markHandedOff(@PathVariable UUID lineItemId,
                                                           @Valid @RequestBody InvoiceHandoffRequest request) {
        return ApiResponse.success("Expense handed off to invoicing", invoiceHandoffService.markHandedOff(lineItemId, request));
    }

    @GetMapping("/summary")
    public ApiResponse<InvoiceHandoffSummaryResponse> getSummary() {
        return ApiResponse.success(invoiceHandoffService.getSummary());
    }

    @GetMapping("/handed-off")
    public ApiResponse<PageResponse<InvoiceHandoffRecordResponse>> getHandedOff(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(invoiceHandoffService.getHandedOff(page, size));
    }

    /** AR's authoritative billing payload (v1). Also callable by the AR service account. */
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
