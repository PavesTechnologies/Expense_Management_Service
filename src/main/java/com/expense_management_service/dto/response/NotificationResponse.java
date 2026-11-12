package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A notification as the recipient sees it: who -> what happened -> which record -> current
 * status -> what to do ({@code actionLabel} + {@code link}). {@code read} is resolved for the
 * caller, including per-person read state on team-inbox notifications.
 */
public record NotificationResponse(
        UUID notificationId,
        String category,
        String eventType,
        String title,
        String message,
        UUID reportId,
        String reportNumber,
        String actorName,
        BigDecimal amount,
        String currencyCode,
        String statusLabel,
        String actionLabel,
        String link,
        /** Set for team-inbox notifications (e.g. FINANCE_EXECUTIVE); null for personal ones. */
        String recipientRole,
        boolean read,
        LocalDateTime sentAt
) {
}
