package com.expense_management_service.dto.external;

import com.expense_management_service.dto.response.BillingPayloadResponse;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What XMS publishes to AR's queue ({@code xms.ar.billing.queue}) for each handoff of a client-billable expense.
 * <p>
 * {@code handoffId} is the idempotency key: a message may arrive more than once (a retry after a lost broker
 * confirmation), and AR should process each {@code handoffId} once. A deliberate re-handoff (an invoice voided and
 * reissued) is a new handoff with its own {@code handoffId}, {@code reprocess = true} and the reason.
 *
 * @param messageVersion version of this envelope; the billing payload carries its own {@code payloadVersion}
 * @param billing        the same billing payload AR can also pull from
 *                       {@code GET /xms/finance/invoice-handoff-queue/{lineItemId}/billing-payload}
 */
public record ArBillingMessage(
        int messageVersion,
        UUID handoffId,
        boolean reprocess,
        String reprocessReason,
        LocalDateTime handedOffAt,
        BillingPayloadResponse billing
) {
    public static final int MESSAGE_VERSION = 1;
}
