package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record VerificationQueryResponse(
        UUID queryId,
        UUID lineItemId,
        String raisedBy,
        String queryText,
        String employeeResponse,
        String status,
        LocalDateTime raisedAt,
        LocalDateTime resolvedAt
) {
}
