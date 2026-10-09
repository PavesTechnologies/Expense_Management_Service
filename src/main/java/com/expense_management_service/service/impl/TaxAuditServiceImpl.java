package com.expense_management_service.service.impl;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.TaxAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaxAuditServiceImpl implements TaxAuditService {

    /** Jackson 2 on purpose: the app registers no Jackson 2 bean (see EmployeeCdcConsumer). Values are pre-stringified. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    /** Joins the caller's transaction, so an audit row is only kept if the change itself commits. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityName, UUID entityId, String action,
                       Map<String, ?> oldValue, Map<String, ?> newValue, AuditSource source, String reason) {
        auditLogRepository.save(AuditLog.builder()
                .entityName(entityName)
                .entityId(entityId)
                .action(action)
                .oldValue(toJson(oldValue))
                .newValue(toJson(newValue))
                .performedBy(performedBy(source))
                .performedAt(LocalDateTime.now())
                .source(source)
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .build());
    }

    private String performedBy(AuditSource source) {
        if (source == AuditSource.SYSTEM || source == AuditSource.OCR) {
            return source.name();
        }
        try {
            return currentUserService.getEmployeeId();
        } catch (RuntimeException ex) {
            // No authenticated user (scheduler / migration-style callers).
            return AuditSource.SYSTEM.name();
        }
    }

    private static String toJson(Map<String, ?> value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialize audit value {} - storing its toString()", value, ex);
            return value.toString();
        }
    }
}
