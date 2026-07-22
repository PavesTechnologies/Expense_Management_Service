package com.expense_management_service.security;

/**
 * Role constants issued by UMS.
 * <p>
 * Role names arrive in the JWT {@code roles} claim without the {@code ROLE_} prefix
 * (e.g. {@code "General"}); {@link JwtAuthConverter} upper-cases and prefixes them
 * before they become {@link org.springframework.security.core.GrantedAuthority}s,
 * so {@code @PreAuthorize("hasRole('GENERAL')")} matches a raw claim value of "General".
 * <p>
 * Only {@code GENERAL} is confirmed by the sample token; add further roles here as
 * UMS defines them rather than inventing new ones in XMS.
 */
public final class RoleConstants {

    public static final String GENERAL = "GENERAL";
    public static final String ROLE_GENERAL = SecurityConstants.ROLE_PREFIX + GENERAL;

    private RoleConstants() {
    }
}
