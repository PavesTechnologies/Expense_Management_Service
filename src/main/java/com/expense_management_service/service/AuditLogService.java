package com.expense_management_service.service;

import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogFacetsResponse;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.enums.AuditSource;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The audit trail is append-only: entries are written by the services whose changes they record,
 * and are never edited or deleted afterwards.
 */
public interface AuditLogService {

    AuditLogResponse create(AuditLogRequest request);

    AuditLogResponse getById(UUID auditId);

    /** Newest first; every filter optional, {@code from}/{@code to} inclusive dates. */
    PageResponse<AuditLogResponse> search(String entityName, UUID entityId, String action, String performedBy,
                                          AuditSource source, LocalDate from, LocalDate to, String q,
                                          int page, int size);

    AuditLogFacetsResponse facets();
}
