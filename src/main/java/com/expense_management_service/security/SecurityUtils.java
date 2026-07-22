package com.expense_management_service.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

import static com.expense_management_service.security.SecurityConstants.CLAIM_OBS_USER_UUID;

/**
 * Static security helpers for contexts where injecting {@link CurrentUserService}
 * isn't practical — e.g. an {@code AuditorAware} bean, static utility code, or a
 * MapStruct expression binding.
 * <p>
 * Prefer {@link CurrentUserService} in Spring-managed beans; reach for this class
 * only where constructor injection isn't available.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<UUID> currentUserUuid() {
        return currentJwtToken()
                .map(token -> token.getToken().getClaimAsString(CLAIM_OBS_USER_UUID))
                .map(UUID::fromString);
    }

    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated();
    }

    public static boolean hasAuthority(String authority) {
        return currentJwtToken()
                .map(token -> token.getAuthorities().stream()
                        .anyMatch(granted -> granted.getAuthority().equals(authority)))
                .orElse(false);
    }

    private static Optional<JwtAuthenticationToken> currentJwtToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return Optional.of(jwtAuthenticationToken);
        }
        return Optional.empty();
    }
}
