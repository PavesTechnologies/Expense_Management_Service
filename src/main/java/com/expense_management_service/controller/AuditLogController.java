package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.service.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuditLogResponse> create(@Valid @RequestBody AuditLogRequest request) {
        return ApiResponse.success("Audit log created", auditLogService.create(request));
    }

    @PutMapping("/{auditId}")
    public ApiResponse<AuditLogResponse> update(@PathVariable UUID auditId, @Valid @RequestBody AuditLogRequest request) {
        return ApiResponse.success("Audit log updated", auditLogService.update(auditId, request));
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditLogResponse> getById(@PathVariable UUID auditId) {
        return ApiResponse.success(auditLogService.getById(auditId));
    }

    @GetMapping
    public ApiResponse<List<AuditLogResponse>> getAll() {
        return ApiResponse.success(auditLogService.getAll());
    }

    @DeleteMapping("/{auditId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID auditId) {
        auditLogService.delete(auditId);
    }
}
