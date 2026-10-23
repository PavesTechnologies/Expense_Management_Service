package com.expense_management_service.enums;

/**
 * Whether a handed-off client-billable expense has reached AR's RabbitMQ queue. Tracked separately from the
 * handoff itself ({@code InvoiceSync.syncStatus = HANDED_OFF}): a handoff is recorded once, its delivery may
 * take several attempts.
 */
public enum ArDeliveryStatus {
    /** Recorded, not yet confirmed by the broker - sent right after the handoff commits. */
    PENDING,
    /** The broker confirmed the message onto AR's queue. */
    SENT,
    /** The last attempt failed; the retry job tries again until the attempt limit, and Finance can resend. */
    FAILED
}
