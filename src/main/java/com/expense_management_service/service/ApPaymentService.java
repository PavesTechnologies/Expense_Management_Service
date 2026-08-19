package com.expense_management_service.service;

import com.expense_management_service.dto.response.ApPaymentDetailsResponse;
import com.expense_management_service.dto.response.ApPaymentQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * AP_EXECUTIVE's own action surface - deliberately separate from {@code
 * FinanceVerificationService}/{@code ApprovalWorkflowService} (no second workflow engine, but a
 * distinct action vocabulary for a distinct role). AP_EXECUTIVE never performs Finance
 * Verification, never approves/rejects, and never edits an expense - the only state transition
 * this service can make is {@code APPROVED_FOR_PAYMENT -> PAYMENT_COMPLETED}, strictly enforced
 * server-side.
 */
public interface ApPaymentService {

    /** Every internal expense currently awaiting external payment confirmation. */
    PageResponse<ApPaymentQueueItemResponse> getApQueue(Pageable pageable);

    /** Full read-only detail view for one report - only reports that have reached {@code APPROVED_FOR_PAYMENT} (or later) are visible here. */
    ApPaymentDetailsResponse getPaymentDetails(UUID reportId);

    /**
     * Confirms the external payment completed. The only valid transition is {@code
     * paymentRoutingStatus == APPROVED_FOR_PAYMENT -> PAYMENT_COMPLETED}; every other current
     * status (including an already-{@code PAYMENT_COMPLETED} report) is rejected. Records who
     * performed the action and when, and writes an {@code AuditLog} entry - XMS never pays
     * anything itself, this only records the confirmation.
     */
    ExpenseReportResponse markPaymentCompleted(UUID reportId, String actingEmployeeId);
}
