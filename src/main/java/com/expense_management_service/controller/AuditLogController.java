package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.AuditLogFacetsResponse;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only view of the audit trail. There is deliberately no create, update or delete here:
 * entries are written by the services whose changes they record, and an audit trail that can be
 * edited through its own API is not an audit trail.
 */
@RestController
@RequestMapping("/xms/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @RequestParam(required = false) String entityName,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String performedBy,
            @RequestParam(required = false) AuditSource source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(auditLogService.search(entityName, entityId, action, performedBy, source,
                from, to, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/facets")
    public ApiResponse<AuditLogFacetsResponse> facets() {
        return ApiResponse.success(auditLogService.facets());
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditLogResponse> getById(@PathVariable UUID auditId) {
        return ApiResponse.success(auditLogService.getById(auditId));
    }
}
