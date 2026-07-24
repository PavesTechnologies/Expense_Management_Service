package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.NotificationRequest;
import com.expense_management_service.dto.response.NotificationResponse;


import java.util.UUID;

public interface NotificationService {

    NotificationResponse create(NotificationRequest request);

    NotificationResponse update(UUID notificationId, NotificationRequest request);

    NotificationResponse getById(UUID notificationId);

    List<NotificationResponse> getAll();

    void delete(UUID notificationId);
}
