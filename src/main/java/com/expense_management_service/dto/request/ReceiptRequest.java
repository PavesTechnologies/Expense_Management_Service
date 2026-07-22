package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReceiptRequest(
        @NotNull UUID lineItemId,
        @NotBlank @Size(max = 255) String fileName,
        @Size(max = 255) String filePath,
        @Size(max = 255) String fileType,
        @PositiveOrZero Integer fileSize,
        @Size(max = 255) String uploadedBy,
        @Size(max = 255) String ocrStatus,
        @Size(max = 255) String fileHash
) {
}
