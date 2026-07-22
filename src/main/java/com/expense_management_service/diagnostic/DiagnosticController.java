package com.expense_management_service.diagnostic;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UserProfileResponse;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.PermissionConstants;
import com.expense_management_service.security.RoleConstants;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic-only endpoints for verifying the UMS integration without a database.
 * <p>
 * Not part of the expense domain API — safe to delete once real XMS endpoints
 * exercise {@link CurrentUserService} / {@link UmsClient} in production code.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diagnostics")
@RequiredArgsConstructor
@Tag(name = "Diagnostics", description = "UMS integration sanity checks (no database required)")
public class DiagnosticController {

    private final CurrentUserService currentUserService;
    private final UmsClient umsClient;

    /**
     * Confirms JWT validation and claim-to-{@link CurrentUser} mapping work,
     * purely from the token already present on the request — no outbound call
     * to UMS is made.
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUser> me() {
        return ApiResponse.success(currentUserService.getCurrentUser());
    }

    /**
     * Confirms the full round trip: forwards the caller's bearer token to UMS
     * via {@link UmsClient} and returns what UMS reports for the same user.
     * Requires UMS to actually be reachable at {@code ums.base-url}.
     */
    @GetMapping("/me/ums-profile")
    public ApiResponse<UserProfileResponse> umsProfile() {
        logSecurityContextForDiagnostics(); // TEMPORARY — remove once the UMS 401 is root-caused.
        return ApiResponse.success(umsClient.getCurrentUser());
    }

    /**
     * TEMPORARY DIAGNOSTIC LOGGING — remove once the UMS 401 is root-caused.
     * Confirms what XMS's own SecurityContext holds for this request, immediately
     * before the outbound call to UMS is made.
     */
    private void logSecurityContextForDiagnostics() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        log.debug("[SEC-CTX] Authentication class: {}",
                authentication == null ? "null" : authentication.getClass().getName());
        log.debug("[SEC-CTX] Principal class: {}",
                authentication == null ? "null" : authentication.getPrincipal().getClass().getName());
        log.debug("[SEC-CTX] Credentials present: {}",
                authentication != null && authentication.getCredentials() != null);

        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            var jwt = jwtAuthenticationToken.getToken();
            log.debug("[SEC-CTX] JWT subject: {}", jwt.getSubject());
            log.debug("[SEC-CTX] JWT issuer: {}", jwt.getIssuer());
            String tokenValue = jwt.getTokenValue();
            log.debug("[SEC-CTX] JWT token value (first 20 chars): {}...",
                    tokenValue.substring(0, Math.min(20, tokenValue.length())));
        }
    }

    /**
     * Confirms permission-based method security is wired correctly. Returns
     * 403 for any caller whose token lacks {@code VIEW_USER_PUBLIC}.
     */
    @GetMapping("/secured/view-user-public")
    @PreAuthorize("hasAuthority('" + PermissionConstants.VIEW_USER_PUBLIC + "')")
    public ApiResponse<String> securedByPermission() {
        return ApiResponse.success("You have VIEW_USER_PUBLIC — permission mapping from the JWT works.");
    }

    /**
     * Confirms role-based method security is wired correctly. Returns 403 for
     * any caller whose token lacks the {@code GENERAL} role.
     */
    @GetMapping("/secured/general-role")
    @PreAuthorize("hasRole('" + RoleConstants.GENERAL + "')")
    public ApiResponse<String> securedByRole() {
        return ApiResponse.success("You have ROLE_GENERAL — role mapping from the JWT works.");
    }
}
