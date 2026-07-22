package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuditLogResponse(
        UUID auditId,
        String entityName,
        UUID entityId,
        String action,
        String oldValue,
        String newValue,
        String performedBy,
        LocalDateTime performedAt
) {
}
