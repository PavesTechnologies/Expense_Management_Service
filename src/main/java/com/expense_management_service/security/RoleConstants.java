package com.expense_management_service.security;

/**
 * Role constants issued by UMS.
 * <p>
 * Role names arrive in the JWT {@code roles} claim without the {@code ROLE_} prefix
 * (e.g. {@code "General"}); {@link JwtAuthConverter} upper-cases and prefixes them
 * before they become {@link org.springframework.security.core.GrantedAuthority}s,
 * so {@code @PreAuthorize("hasRole('GENERAL')")} matches a raw claim value of "General".
 * <p>
 * {@code GENERAL} is confirmed by the sample token — it is the general employee/staff
 * role for XMS (formerly modeled here as a separate {@code EMPLOYEE} role; UMS issues
 * only {@code GENERAL}, so that distinction was removed). {@code ADMIN}, {@code FINANCE},
 * and {@code MANAGER} are the other XMS-module roles expected for expense management RBAC
 * (GL Account, Expense Category, Cost Center administration, approvals, etc.) — confirm
 * the exact claim values with the UMS team before relying on them in production checks.
 */
public final class RoleConstants {

    public static final String GENERAL = "GENERAL";
    public static final String ROLE_GENERAL = SecurityConstants.ROLE_PREFIX + GENERAL;

    public static final String ADMIN = "ADMIN";
    public static final String ROLE_ADMIN = SecurityConstants.ROLE_PREFIX + ADMIN;

    public static final String FINANCE = "FINANCE";
    public static final String ROLE_FINANCE = SecurityConstants.ROLE_PREFIX + FINANCE;

    public static final String MANAGER = "MANAGER";
    public static final String ROLE_MANAGER = SecurityConstants.ROLE_PREFIX + MANAGER;

    private RoleConstants() {
    }
}
