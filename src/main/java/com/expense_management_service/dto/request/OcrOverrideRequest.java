package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reported by the frontend when an employee edits an OCR-prefilled value before saving. */
public record OcrOverrideRequest(
        @NotBlank @Size(max = 100) String fieldName,
        @Size(max = 255) String originalValue,
        @Size(max = 255) String overriddenValue,
        @Size(max = 500) String reason
) {
}
