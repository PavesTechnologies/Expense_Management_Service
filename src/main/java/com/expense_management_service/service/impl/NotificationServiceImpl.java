package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.NotificationRequest;
import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.entity.Notification;
import com.expense_management_service.mapper.NotificationMapper;
import com.expense_management_service.repository.NotificationRepository;
import com.expense_management_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;

    @Override
    public NotificationResponse create(NotificationRequest request) {
        Notification entity = notificationMapper.toEntity(request);
        entity.setSentAt(LocalDateTime.now());
        return notificationMapper.toResponse(notificationRepository.save(entity));
    }

    @Override
    public NotificationResponse update(UUID notificationId, NotificationRequest request) {
        Notification entity = findEntity(notificationId);
        notificationMapper.updateEntity(entity, request);
        return notificationMapper.toResponse(notificationRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getById(UUID notificationId) {
        return notificationMapper.toResponse(findEntity(notificationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getAll(Pageable pageable) {
        return notificationRepository.findAll(pageable).map(notificationMapper::toResponse);
    }

    @Override
    public void delete(UUID notificationId) {
        notificationRepository.delete(findEntity(notificationId));
    }

    private Notification findEntity(UUID notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));
    }
}
