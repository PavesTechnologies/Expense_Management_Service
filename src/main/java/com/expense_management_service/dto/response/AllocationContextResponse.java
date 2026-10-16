package com.expense_management_service.dto.response;

/**
 * What the expense form needs to know to ask for a cross-department reason: the caller's home
 * department, compared with each chosen cost center's {@code departmentUuid}. Null when the
 * employee directory has no department for the caller (no reason is then required).
 */
public record AllocationContextResponse(String homeDepartmentUuid) {
}
