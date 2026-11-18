package com.expense_management_service.dto.request;

import jakarta.validation.constraints.Size;

/** A second Finance user's decision on a release request. Reason is required when rejecting. */
public record ReceiptReleaseDecisionRequest(
        @Size(max = 500) String reason
) {
}
