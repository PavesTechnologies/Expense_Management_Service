package com.expense_management_service.event;

import java.util.UUID;

/**
 * Published by {@code ReceiptServiceImpl} right after a receipt is durably saved.
 * <p>
 * Deliberately carries nothing but the id. {@code ReceiptService} publishing this knows only
 * "a receipt was uploaded" — it has no dependency on OCR, and no idea {@link OcrEventListener}
 * (or anything else) is listening. Interpreting this event as "go run OCR" is entirely
 * {@code OcrEventListener}'s responsibility.
 */
public record ReceiptUploadedEvent(UUID receiptId) {
}
