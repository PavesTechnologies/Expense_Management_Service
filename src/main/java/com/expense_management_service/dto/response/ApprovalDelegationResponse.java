package com.expense_management_service.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record ApprovalDelegationResponse(
        UUID delegationId,
        String delegatorId,
        String delegateId,
        LocalDate startDate,
        LocalDate endDate,
        String status
) {
}
