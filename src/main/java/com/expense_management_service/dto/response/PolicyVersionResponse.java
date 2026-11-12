package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record PolicyVersionResponse(
        UUID versionId,
        UUID policyId,
        int versionNumber,
        LocalDateTime activatedAt
) {
}
