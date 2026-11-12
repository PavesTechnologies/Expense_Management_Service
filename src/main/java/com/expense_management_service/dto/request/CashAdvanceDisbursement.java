package com.expense_management_service.dto.request;

import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * How Finance paid out an approved cash advance, and the proof for that payment.
 * Only the fields for {@code disbursementMode} are used; the rest are ignored.
 * <ul>
 *   <li>CASH: {@code receivedByName} and {@code signedAcknowledgment} (PDF, JPG or PNG).</li>
 *   <li>BANK_TRANSFER: {@code transactionReference}, {@code transferDate}, {@code transferDetails}.</li>
 *   <li>UPI: {@code upiTransactionId}, {@code payeeUpiId}, {@code transferDate}, {@code paymentProof} (PDF, JPG or PNG).</li>
 * </ul>
 */
public record CashAdvanceDisbursement(
        String disbursementMode,
        String receivedByName,
        MultipartFile signedAcknowledgment,
        String transactionReference,
        LocalDate transferDate,
        String transferDetails,
        String upiTransactionId,
        String payeeUpiId,
        MultipartFile paymentProof,
        String confirmationNote
) {
}
