package com.expense_management_service.service;

import com.expense_management_service.dto.response.InvoiceSyncResponse;

import java.util.List;
import java.util.UUID;

/** Delivers handed-off client-billable expenses to AR's RabbitMQ queue, and tracks each delivery until it lands. */
public interface ArBillingDeliveryService {

    /**
     * Sends one handoff to AR, in its own transaction, and records the outcome on it (SENT, or FAILED with the
     * error). A handoff already SENT is left alone, so a repeated call never sends it twice from XMS's side.
     */
    InvoiceSyncResponse deliver(UUID syncId);

    /** Finance's manual resend of a handoff that hasn't reached AR (FAILED, PENDING, or recorded before delivery existed). */
    InvoiceSyncResponse redeliver(UUID syncId);

    /** Handoffs the retry job should send now - see {@code InvoiceSyncRepository.findDueForArDelivery}. */
    List<UUID> findDueForDelivery();
}
