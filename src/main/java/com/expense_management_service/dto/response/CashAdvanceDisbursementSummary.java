package com.expense_management_service.dto.response;

import com.expense_management_service.entity.CashAdvance;

import java.time.LocalDate;

/**
 * How Finance paid out a cash advance, so the employee's return can use the same mode.
 * {@code reference} is the bank transaction reference (BANK_TRANSFER) or the UPI transaction ID (UPI).
 * The proof file itself is not returned; {@code proofOnFile} says whether one was uploaded.
 */
public record CashAdvanceDisbursementSummary(
        String mode,
        LocalDate paymentDate,
        String reference,
        String receivedByName,
        String payeeUpiId,
        String transferDetails,
        boolean proofOnFile
) {

    /** Null when Finance has not recorded a payout for this advance yet. */
    public static CashAdvanceDisbursementSummary from(CashAdvance advance) {
        if (advance.getDisbursementMode() == null) {
            return null;
        }
        return new CashAdvanceDisbursementSummary(
                advance.getDisbursementMode(),
                advance.getDisbursementPaymentDate(),
                advance.getDisbursementReference(),
                advance.getDisbursementAcknowledgedBy(),
                advance.getDisbursementPayeeUpiId(),
                advance.getDisbursementTransferDetails(),
                advance.getDisbursementProofReference() != null
        );
    }
}
