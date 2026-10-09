package com.expense_management_service.service;

import com.expense_management_service.dto.response.NotificationResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.enums.NotificationCategory;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Workflow notifications. Producers call {@link #notifyEmployee} / {@link #notifyRole}; each
 * notification is stored and pushed live over WebSocket once the surrounding transaction commits.
 * The read side is always scoped to the caller: their own notifications plus their roles' team inboxes.
 */
public interface NotificationService {

    /** Team inboxes. Values match JWT roles upper-cased. */
    String ROLE_FINANCE = "FINANCE_EXECUTIVE";
    String ROLE_AP = "AP_EXECUTIVE";
    String ROLE_ADMIN = "ADMIN";

    void notifyEmployee(String employeeId, NotificationDraft draft);

    void notifyRole(String role, NotificationDraft draft);

    /** @param readFilter all / read / unread */
    PageResponse<NotificationResponse> search(String readFilter, NotificationCategory category, String eventType,
                                              LocalDate from, LocalDate to, String q, int page, int size);

    long unreadCount();

    void markRead(UUID notificationId);

    int markAllRead();
}
