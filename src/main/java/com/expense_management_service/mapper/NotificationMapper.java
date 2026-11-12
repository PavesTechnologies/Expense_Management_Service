package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.entity.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {

    /** @param read already resolved for the caller (team notifications have per-person read state) */
    public NotificationResponse toResponse(Notification n, boolean read) {
        return new NotificationResponse(
                n.getNotificationId(),
                n.getCategory() != null ? n.getCategory().name() : null,
                n.getEventType(),
                n.getTitle(),
                n.getMessage(),
                n.getReportId(),
                n.getReportNumber(),
                n.getActorName(),
                n.getAmount(),
                n.getCurrencyCode(),
                n.getStatusLabel(),
                n.getActionLabel(),
                n.getLink(),
                n.getRecipientRole(),
                read,
                n.getSentAt()
        );
    }
}
