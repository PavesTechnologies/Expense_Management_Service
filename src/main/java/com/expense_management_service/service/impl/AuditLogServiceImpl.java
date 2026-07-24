package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.mapper.AuditLogMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    public AuditLogResponse update(UUID auditId, AuditLogRequest request) {
        AuditLog entity = findEntity(auditId);
        auditLogMapper.updateEntity(entity, request);
        return auditLogMapper.toResponse(auditLogRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogResponse getById(UUID auditId) {
        return auditLogMapper.toResponse(findEntity(auditId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAll() {
        return auditLogRepository.findAll().stream().map(auditLogMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID auditId) {
        auditLogRepository.delete(findEntity(auditId));
    }

    private AuditLog findEntity(UUID auditId) {
        return auditLogRepository.findById(auditId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog not found with id: " + auditId));
    }
}
