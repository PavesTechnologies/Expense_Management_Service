package com.expense_management_service.service;

import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;

import java.util.UUID;

/**
 * Turns a reviewed receipt into a real, fully-valid {@code ExpenseLineItem} — the one point
 * where OCR data crosses into the ExpenseLineItem module.
 * <p>
 * Deliberately separate from {@link OCRService} (which only orchestrates extraction — it never
 * creates a line item) and from {@code ExpenseLineItemService} (untouched; reused as-is for
 * actual line item creation, so its validation/currency-conversion/policy-evaluation logic isn't
 * duplicated here). This class's only responsibility is bridging the two.
 */
public interface ReceiptConfirmationService {

    /**
     * Creates a new {@code ExpenseLineItem} from the receipt's latest completed OCR result (if
     * any) merged with whatever the employee supplied/overrode in {@code request}, or — if
     * {@code request.lineItemId()} is provided — links the receipt to that existing line item
     * instead and reports whether its amount differs from what OCR extracted.
     */
    ReceiptConfirmResponse confirm(UUID receiptId, ReceiptConfirmRequest request);
}
