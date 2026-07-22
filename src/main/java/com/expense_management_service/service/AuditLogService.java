package com.expense_management_service.service;

import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AuditLogService {

    AuditLogResponse create(AuditLogRequest request);

    AuditLogResponse update(UUID auditId, AuditLogRequest request);

    AuditLogResponse getById(UUID auditId);

    Page<AuditLogResponse> getAll(Pageable pageable);

    void delete(UUID auditId);
}
