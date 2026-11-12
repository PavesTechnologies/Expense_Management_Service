package com.expense_management_service.dto.response;

import com.expense_management_service.enums.OcrStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param amountMismatch  true only in "attach to existing line item" mode, when OCR extracted an
 *                        amount that differs from what's already on the line item. Advisory
 *                        only — never blocks the confirmation.
 * @param extractedAmount the receipt's latest OCR amount, or {@code null} if OCR never completed
 */
public record ReceiptConfirmResponse(
        UUID receiptId,
        UUID lineItemId,
        OcrStatus receiptStatus,
        boolean amountMismatch,
        BigDecimal enteredAmount,
        BigDecimal extractedAmount
) {
}
