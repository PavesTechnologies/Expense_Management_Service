package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogResponse;


import java.util.UUID;

public interface AuditLogService {

    AuditLogResponse create(AuditLogRequest request);

    AuditLogResponse update(UUID auditId, AuditLogRequest request);

    AuditLogResponse getById(UUID auditId);

    List<AuditLogResponse> getAll();

    void delete(UUID auditId);
}
