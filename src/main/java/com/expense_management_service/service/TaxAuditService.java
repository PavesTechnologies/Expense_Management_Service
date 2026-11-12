package com.expense_management_service.service;

import com.expense_management_service.enums.AuditSource;

import java.util.Map;
import java.util.UUID;

/**
 * Writes tax-related changes (tax codes, category mappings, line tax) to {@code audit_log} with
 * the source and, where the rules require one, the reason. Old / new values are compact JSON.
 */
public interface TaxAuditService {

    void record(String entityName, UUID entityId, String action,
                Map<String, ?> oldValue, Map<String, ?> newValue, AuditSource source, String reason);
}
