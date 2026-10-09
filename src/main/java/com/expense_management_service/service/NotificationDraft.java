package com.expense_management_service.service;

import com.expense_management_service.enums.NotificationCategory;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/** Everything a producer supplies for a notification; the recipient is given separately. */
@Builder(toBuilder = true)
public record NotificationDraft(
        NotificationCategory category,
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
        String link
) {
}
