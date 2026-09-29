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

    PageResponse<InvoiceHandoffEligibleExpenseResponse> getEligibleExpenses(
            UUID clientId, UUID projectId, LocalDate startDate, LocalDate endDate, int page, int size);

    InvoiceSyncResponse markHandedOff(UUID lineItemId, InvoiceHandoffRequest request);

    List<InvoiceSyncResponse> getHandoffHistory(UUID lineItemId);

    /** Every completed handoff, newest first. */
    PageResponse<InvoiceHandoffRecordResponse> getHandedOff(int page, int size);

    InvoiceHandoffSummaryResponse getSummary();
}
