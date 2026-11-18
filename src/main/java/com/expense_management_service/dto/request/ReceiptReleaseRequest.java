package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Finance asks for a rejected receipt to be released for a supplementary claim. A reason is required. */
public record ReceiptReleaseRequest(
        @NotNull UUID receiptId,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
