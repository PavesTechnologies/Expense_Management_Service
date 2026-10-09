package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Whole-report reject (§6) - terminal, comment required, distinct from line-level Needs Correction. */
public record RejectReportRequest(
        @NotBlank @Size(max = 4000) String comment
) {
}
