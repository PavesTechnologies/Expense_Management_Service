package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.NotificationRequest;
import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/employee/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NotificationResponse> create(@Valid @RequestBody NotificationRequest request) {
        return ApiResponse.success("Notification created", notificationService.create(request));
    }

    @PutMapping("/{notificationId}")
    public ApiResponse<NotificationResponse> update(@PathVariable UUID notificationId,
                                                     @Valid @RequestBody NotificationRequest request) {
        return ApiResponse.success("Notification updated", notificationService.update(notificationId, request));
    }

    @GetMapping("/{notificationId}")
    public ApiResponse<NotificationResponse> getById(@PathVariable UUID notificationId) {
        return ApiResponse.success(notificationService.getById(notificationId));
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> getAll() {
        return ApiResponse.success(notificationService.getAll());
    }

    @DeleteMapping("/{notificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID notificationId) {
        notificationService.delete(notificationId);
    }
}
