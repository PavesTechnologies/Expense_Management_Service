package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        String employeeId,
        String notificationType,
        String title,
        String message,
        Boolean isRead,
        LocalDateTime sentAt
) {
}
