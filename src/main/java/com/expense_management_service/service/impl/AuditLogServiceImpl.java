package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogFacetsResponse;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.mapper.AuditLogMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogMapper auditLogMapper;

    @Override
    public AuditLogResponse create(AuditLogRequest request) {
        AuditLog entity = auditLogMapper.toEntity(request);
        entity.setPerformedAt(LocalDateTime.now());
        return auditLogMapper.toResponse(auditLogRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogResponse getById(UUID auditId) {
        return auditLogMapper.toResponse(findEntity(auditId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String entityName, UUID entityId, String action, String performedBy,
                                                 AuditSource source, LocalDate from, LocalDate to, String q,
                                                 int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        Page<AuditLog> result = auditLogRepository.search(
                StringUtils.hasText(entityName) ? entityName : null,
                entityId,
                StringUtils.hasText(action) ? action : null,
                StringUtils.hasText(performedBy) ? performedBy.trim() : null,
                source,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
                StringUtils.hasText(q) ? "%" + q.trim().toLowerCase(Locale.ROOT) + "%" : null,
                PageRequest.of(page, size));
        return PageResponse.of(result.map(auditLogMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogFacetsResponse facets() {
        return new AuditLogFacetsResponse(
                auditLogRepository.findDistinctEntityNames(),
                auditLogRepository.findDistinctActions(),
                Arrays.stream(AuditSource.values()).map(Enum::name).toList());
    }

    private AuditLog findEntity(UUID auditId) {
        return auditLogRepository.findById(auditId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog not found with id: " + auditId));
    }
}
