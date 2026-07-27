package com.expense_management_service.common.exception;

/**
 * Thrown when a request is well-formed and references valid entities but violates a
 * workflow/business rule — e.g. editing a report that is no longer in an editable status.
 * Distinct from {@link DuplicateResourceException} (409, resource conflict) and plain
 * {@link IllegalArgumentException} (400, invalid/inactive reference) — this maps to
 * 422 Unprocessable Entity.
 */
public class BusinessRuleViolationException extends RuntimeException {

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
