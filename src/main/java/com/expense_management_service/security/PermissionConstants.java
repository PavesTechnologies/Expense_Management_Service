package com.expense_management_service.security;

/**
 * Permission (fine-grained authority) constants issued by UMS.
 * <p>
 * These values must exactly match the {@code permissions} claim values UMS embeds
 * in the JWT — XMS does not define, store, or own permissions, it only references
 * them in {@code @PreAuthorize} checks.
 * <p>
 * The {@code VIEW_USER_*} / {@code EDIT_OWN_PROFILE} entries mirror the sample
 * token supplied in the integration spec. The {@code EXPENSE_*} entries are
 * illustrative XMS-module permissions expected to be registered in UMS's
 * permission registry before they can appear in a real token — confirm the
 * exact names with the UMS team before relying on them in production checks.
 */
public final class PermissionConstants {

    // Confirmed present in the sample UMS token
    public static final String VIEW_USER_PUBLIC = "VIEW_USER_PUBLIC";
    public static final String VIEW_USER_ALL = "VIEW_USER_ALL";
    public static final String EDIT_OWN_PROFILE = "EDIT_OWN_PROFILE";

    // XMS-module permissions (illustrative — must be registered in UMS before use)
    public static final String EXPENSE_CREATE = "EXPENSE_CREATE";
    public static final String EXPENSE_VIEW = "EXPENSE_VIEW";
    public static final String EXPENSE_EDIT = "EXPENSE_EDIT";
    public static final String EXPENSE_DELETE = "EXPENSE_DELETE";
    public static final String EXPENSE_SUBMIT = "EXPENSE_SUBMIT";
    public static final String EXPENSE_APPROVE = "EXPENSE_APPROVE";

    private PermissionConstants() {
    }
}
