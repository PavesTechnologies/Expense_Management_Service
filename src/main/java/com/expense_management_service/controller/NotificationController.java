package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's Notification Center: their personal notifications plus their roles' team inboxes.
 * Notifications are produced by the workflow (see NotificationEventListener), never created here.
 */
@RestController
@RequestMapping("/xms/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> search(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) NotificationCategory category,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(notificationService.search(status, category, eventType, from, to, q, page, Math.min(size, 100)));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.success(Map.of("count", notificationService.unreadCount()));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(@PathVariable UUID notificationId) {
        notificationService.markRead(notificationId);
        return ApiResponse.success(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        return ApiResponse.success(Map.of("marked", notificationService.markAllRead()));
    }
}
