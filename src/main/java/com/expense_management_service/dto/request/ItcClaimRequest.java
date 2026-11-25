package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Marks expense lines' input tax credit as claimed in a GST return, or undoes that.
 *
 * @param lineItemIds  the lines to mark (each must be Finance-verified, recoverable, with a supplier GSTIN)
 * @param returnPeriod the GSTR-3B month the credit was claimed in, yyyy-MM (e.g. "2026-11"); not used when undoing
 * @param note         optional note, e.g. the return's ARN; required as the reason when undoing a claim
 */
public record ItcClaimRequest(
        @NotEmpty @Size(max = 500) List<UUID> lineItemIds,
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "returnPeriod must be yyyy-MM") String returnPeriod,
        @Size(max = 500) String note
) {
}
