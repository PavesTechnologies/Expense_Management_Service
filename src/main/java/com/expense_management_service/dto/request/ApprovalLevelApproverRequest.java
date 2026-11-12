package com.expense_management_service.dto.request;

import com.expense_management_service.enums.ApproverSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code sourceReference} is required (and only meaningful) when {@code sourceType == NAMED_USER}. */
public record ApprovalLevelApproverRequest(
        Integer entryOrder,
        @NotNull ApproverSourceType sourceType,
        @Size(max = 255) String sourceReference
) {
}
