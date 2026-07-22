package com.expense_management_service.security;

/**
 * Central place for security-related constant values used across XMS.
 * <p>
 * Keeping these values here avoids magic strings scattered across
 * {@link JwtAuthConverter}, {@link CurrentUserService} and configuration classes.
 */
public final class SecurityConstants {

    /** Prefix Spring Security expects on role-based authorities (e.g. {@code hasRole("GENERAL")}). */
    public static final String ROLE_PREFIX = "ROLE_";

    /** Name of the JWT claim holding the UMS user's stable identity UUID. */
    public static final String CLAIM_OBS_USER_UUID = "obs_user_uuid";

    /** Name of the JWT claim holding the numeric UMS user id (never persisted by XMS). */
    public static final String CLAIM_USER_ID = "user_id";

    /** Name of the JWT claim holding the employee id. */
    public static final String CLAIM_EMPLOYEE_ID = "employee_id";

    /** Name of the JWT claim holding the user's email address. */
    public static final String CLAIM_EMAIL = "email";

    /** Name of the JWT claim holding the user's display name. */
    public static final String CLAIM_NAME = "name";

    /** Name of the JWT claim holding the list of role names. */
    public static final String CLAIM_ROLES = "roles";

    /** Name of the JWT claim holding the list of permission names. */
    public static final String CLAIM_PERMISSIONS = "permissions";

    /** HTTP header carrying the bearer token, forwarded as-is to UMS by {@code RestClientConfig}. */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer token scheme prefix. */
    public static final String BEARER_PREFIX = "Bearer ";

    private SecurityConstants() {
    }
}
