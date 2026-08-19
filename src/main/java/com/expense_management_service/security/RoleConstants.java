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
 * <p>
 * {@code FINANCE_EXECUTIVE} is a distinct, additive role scoped specifically to Finance
 * Verification (see {@code FinanceVerificationController}) - deliberately separate from
 * the pre-existing {@code FINANCE} role, which already gates unrelated XMS modules (GL
 * Account, Currency, Policy administration, etc.) and is left untouched. Same "expected,
 * confirm with UMS" caveat applies.
 * <p>
 * {@code AP_EXECUTIVE} is likewise distinct and additive, scoped only to {@code
 * ApPaymentController} (confirming external payment completion) - it never performs Finance
 * Verification, never approves/rejects, and cannot act on a report outside {@code
 * APPROVED_FOR_PAYMENT}. Same "expected, confirm with UMS" caveat applies.
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

    public static final String FINANCE_EXECUTIVE = "FINANCE_EXECUTIVE";
    public static final String ROLE_FINANCE_EXECUTIVE = SecurityConstants.ROLE_PREFIX + FINANCE_EXECUTIVE;

    public static final String AP_EXECUTIVE = "AP_EXECUTIVE";
    public static final String ROLE_AP_EXECUTIVE = SecurityConstants.ROLE_PREFIX + AP_EXECUTIVE;

    private RoleConstants() {
    }
}
