package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationRequest(
        @NotBlank @Size(max = 255) String employeeId,
        @Size(max = 255) String notificationType,
        @Size(max = 255) String title,
        String message,
        Boolean isRead
) {
}
