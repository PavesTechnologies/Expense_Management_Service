package com.expense_management_service.dto.response;

import com.expense_management_service.enums.CashAdvanceDocumentCategory;

import java.time.LocalDateTime;
import java.util.UUID;

/** A supporting document as the employee and approvers see it. The storage key is never exposed. */
public record CashAdvanceDocumentResponse(
        UUID documentId,
        UUID advanceId,
        CashAdvanceDocumentCategory category,
        String originalFileName,
        String contentType,
        long fileSizeBytes,
        String uploadedBy,
        LocalDateTime uploadedAt
) {
}
