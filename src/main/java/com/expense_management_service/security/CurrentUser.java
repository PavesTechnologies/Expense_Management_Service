package com.expense_management_service.security;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of the authenticated caller, derived from the validated
 * UMS-issued JWT for the current request.
 * <p>
 * {@code uuid} is {@code obs_user_uuid} — the identifier XMS persists on every
 * audit/workflow column ({@code created_by_uuid}, {@code approved_by_uuid}, etc.),
 * never the numeric {@code user_id} claim.
 *
 * @param uuid        stable UMS identity UUID ({@code obs_user_uuid} claim)
 * @param employeeId  employee id ({@code employee_id} claim)
 * @param email       user email ({@code email} claim)
 * @param name        display name ({@code name} claim)
 * @param roles       raw role names as issued by UMS, without the {@code ROLE_} prefix
 * @param permissions fine-grained permission names as issued by UMS
 */
public record CurrentUser(
        UUID uuid,
        String employeeId,
        String email,
        String name,
        List<String> roles,
        List<String> permissions
) {
}
