package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.NotificationRequest;
import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.entity.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {

    public Notification toEntity(NotificationRequest request) {
        return Notification.builder()
                .employeeId(request.employeeId())
                .notificationType(request.notificationType())
                .title(request.title())
                .message(request.message())
                .isRead(request.isRead())
                .build();
    }

    public void updateEntity(Notification entity, NotificationRequest request) {
        entity.setEmployeeId(request.employeeId());
        entity.setNotificationType(request.notificationType());
        entity.setTitle(request.title());
        entity.setMessage(request.message());
        entity.setIsRead(request.isRead());
    }

    public NotificationResponse toResponse(Notification entity) {
        return new NotificationResponse(
                entity.getNotificationId(),
                entity.getEmployeeId(),
                entity.getNotificationType(),
                entity.getTitle(),
                entity.getMessage(),
                entity.getIsRead(),
                entity.getSentAt()
        );
    }
}
