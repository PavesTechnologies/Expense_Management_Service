package com.expense_management_service.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.SecurityConstants.CLAIM_EMAIL;
import static com.expense_management_service.security.SecurityConstants.CLAIM_EMPLOYEE_ID;
import static com.expense_management_service.security.SecurityConstants.CLAIM_NAME;
import static com.expense_management_service.security.SecurityConstants.CLAIM_OBS_USER_UUID;
import static com.expense_management_service.security.SecurityConstants.CLAIM_PERMISSIONS;
import static com.expense_management_service.security.SecurityConstants.CLAIM_ROLES;

/**
 * Reads the currently authenticated caller off the {@link SecurityContextHolder}.
 * <p>
 * All claims originate from the JWT that Spring Security already validated via
 * UMS's JWKS endpoint; this service only extracts and shapes them into a
 * {@link CurrentUser}. It never calls out to UMS itself — use {@code UmsClient}
 * when data beyond what's in the token is required (e.g. the full employee record).
 */
@Service
public class CurrentUserService {

    public CurrentUser getCurrentUser() {
        var jwt = currentToken().getToken();

        return new CurrentUser(
                UUID.fromString(jwt.getClaimAsString(CLAIM_OBS_USER_UUID)),
                jwt.getClaimAsString(CLAIM_EMPLOYEE_ID),
                jwt.getClaimAsString(CLAIM_EMAIL),
                jwt.getClaimAsString(CLAIM_NAME),
                jwt.getClaimAsStringList(CLAIM_ROLES),
                jwt.getClaimAsStringList(CLAIM_PERMISSIONS)
        );
    }

    public UUID getUserUuid() {
        return getCurrentUser().uuid();
    }

    public String getEmployeeId() {
        return getCurrentUser().employeeId();
    }

    public String getEmail() {
        return getCurrentUser().email();
    }

    public List<String> getPermissions() {
        return getCurrentUser().permissions();
    }

    /** Raw bearer token value, e.g. for forwarding to a downstream service call. */
    public String getTokenValue() {
        return currentToken().getToken().getTokenValue();
    }

    private JwtAuthenticationToken currentToken() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
            throw new IllegalStateException(
                    "No authenticated JwtAuthenticationToken found in the security context");
        }
        return jwtAuthenticationToken;
    }
}
