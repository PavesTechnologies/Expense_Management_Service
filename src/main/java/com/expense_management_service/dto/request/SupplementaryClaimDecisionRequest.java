package com.expense_management_service.dto.request;

import jakarta.validation.constraints.Size;

/** Reason for a reject or clarification. Required for those actions; ignored for approval. */
public record SupplementaryClaimDecisionRequest(
        @Size(max = 2000) String comment
) {
}
