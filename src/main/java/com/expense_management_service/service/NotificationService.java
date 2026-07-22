package com.expense_management_service.service;

import com.expense_management_service.dto.request.NotificationRequest;
import com.expense_management_service.dto.response.NotificationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    NotificationResponse create(NotificationRequest request);

    NotificationResponse update(UUID notificationId, NotificationRequest request);

    NotificationResponse getById(UUID notificationId);

    Page<NotificationResponse> getAll(Pageable pageable);

    void delete(UUID notificationId);
}
