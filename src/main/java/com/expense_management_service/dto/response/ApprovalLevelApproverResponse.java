package com.expense_management_service.dto.response;

import com.expense_management_service.enums.ApproverSourceType;

import java.util.UUID;

public record ApprovalLevelApproverResponse(
        UUID entryId,
        Integer entryOrder,
        ApproverSourceType sourceType,
        String sourceReference
) {
}
