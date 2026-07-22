package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AuditLogRequest(
        @NotBlank @Size(max = 255) String entityName,
        UUID entityId,
        @NotBlank @Size(max = 255) String action,
        String oldValue,
        String newValue,
        @Size(max = 255) String performedBy
) {
}
