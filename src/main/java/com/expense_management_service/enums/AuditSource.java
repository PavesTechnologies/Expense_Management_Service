package com.expense_management_service.enums;

/** Who or what made an audited change ({@code audit_log.source}). */
public enum AuditSource {
    EMPLOYEE,
    OCR,
    FINANCE,
    ADMIN,
    SYSTEM
}
