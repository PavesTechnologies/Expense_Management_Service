package com.expense_management_service.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Marks a finance-verified, client-billable line item as handed off to invoicing.
 * <p>
 * {@code allowReprocess} must be {@code true} (with a {@code reason}) to hand off a line item
 * that already has an active {@code HANDED_OFF} record — otherwise the request is rejected as a
 * duplicate. This is the "explicit correction or reprocessing" exception the handoff-uniqueness
 * rule allows for.
 */
public record InvoiceHandoffRequest(
        @Size(max = 255) String invoiceReference,
        Boolean allowReprocess,
        @Size(max = 500) String reason
) {
}
