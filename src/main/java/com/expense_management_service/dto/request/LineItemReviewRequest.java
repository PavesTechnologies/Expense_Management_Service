package com.expense_management_service.dto.request;

import com.expense_management_service.enums.LineItemReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code decision} must be APPROVED or NEEDS_CORRECTION (never PENDING). {@code comment} is required when NEEDS_CORRECTION (§4.2). */
public record LineItemReviewRequest(
        @NotNull LineItemReviewStatus decision,
        @Size(max = 4000) String comment
) {
}
