package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server-side rules for recording how a cash advance was disbursed. These mirror the frontend checks in
 * {@code disbursementConfirmation.js}; the server is the authority, so they run again here.
 * A failed rule throws {@link IllegalArgumentException} (HTTP 400) before any state is changed.
 */
public final class CashAdvanceDisbursementRules {

    public static final String CASH = "CASH";
    public static final String BANK_TRANSFER = "BANK_TRANSFER";
    public static final String UPI = "UPI";

    public static final long MAX_PROOF_BYTES = 10L * 1024 * 1024;

    private static final Map<String, String> PROOF_EXTENSIONS = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/png", "png");

    // Loose UPI VPA check (name@handle). The bank validates the exact address.
    private static final Pattern UPI_ID = Pattern.compile("^[\\w.-]{2,256}@[A-Za-z]{2,64}$");

    private CashAdvanceDisbursementRules() {
    }

    /** Normalised mode (upper case), or throws when the mode is missing or unknown. */
    public static String normalizedMode(String mode) {
        if (isBlank(mode)) {
            throw new IllegalArgumentException("Select how the payment was made.");
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!CASH.equals(normalized) && !BANK_TRANSFER.equals(normalized) && !UPI.equals(normalized)) {
            throw new IllegalArgumentException("Unknown disbursement mode: " + mode);
        }
        return normalized;
    }

    /** Validates the confirmation for its mode. {@code today} bounds the transfer date. */
    public static void validate(CashAdvanceDisbursement disbursement, LocalDate today) {
        if (disbursement == null) {
            throw new IllegalArgumentException("Select how the payment was made.");
        }
        String mode = normalizedMode(disbursement.disbursementMode());

        switch (mode) {
            case CASH -> {
                requireText(disbursement.receivedByName(), "Enter the name of the person who received the cash.");
                validateProof(disbursement.signedAcknowledgment(), "signed acknowledgment");
            }
            case BANK_TRANSFER -> {
                requireText(disbursement.transactionReference(), "Enter the transaction reference.");
                if (disbursement.transferDate() == null) {
                    throw new IllegalArgumentException("Enter the transfer date.");
                }
                requireText(disbursement.transferDetails(), "Enter the transfer details (bank and beneficiary account).");
            }
            case UPI -> {
                requireText(disbursement.upiTransactionId(), "Enter the UPI transaction ID.");
                if (isBlank(disbursement.payeeUpiId()) || !UPI_ID.matcher(disbursement.payeeUpiId().trim()).matches()) {
                    throw new IllegalArgumentException("Enter a valid payee UPI ID (for example name@bank).");
                }
                if (disbursement.transferDate() == null) {
                    throw new IllegalArgumentException("Enter the payment date.");
                }
                validateProof(disbursement.paymentProof(), "payment screenshot");
            }
            default -> throw new IllegalArgumentException("Unknown disbursement mode: " + mode);
        }

        if (disbursement.transferDate() != null && disbursement.transferDate().isAfter(today)) {
            throw new IllegalArgumentException("Transfer date cannot be in the future.");
        }
    }

    /** File extension for an accepted proof file (pdf, jpg or png). */
    public static String proofExtension(MultipartFile file) {
        return PROOF_EXTENSIONS.get(file.getContentType().toLowerCase(Locale.ROOT));
    }

    private static void validateProof(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload the " + label + ".");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!PROOF_EXTENSIONS.containsKey(contentType)) {
            throw new IllegalArgumentException("Upload a PDF, JPG or PNG file.");
        }
        if (file.getSize() > MAX_PROOF_BYTES) {
            throw new IllegalArgumentException("The file must be 10 MB or smaller.");
        }
    }

    private static void requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
