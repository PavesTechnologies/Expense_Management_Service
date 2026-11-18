package com.expense_management_service.event;

import java.util.UUID;

/**
 * Published by {@code ReceiptServiceImpl} when Finance/AP rejects a late receipt. The managers who approved the report are
 * told about it (see {@code LateReceiptNotificationListener}); they are informed only and have nothing to decide.
 *
 * @param receiptId  the rejected late receipt
 * @param reviewerId the Finance/AP employee who rejected it, so they are not notified of their own decision
 */
public record LateReceiptRejectedEvent(UUID receiptId, String reviewerId) {
}
