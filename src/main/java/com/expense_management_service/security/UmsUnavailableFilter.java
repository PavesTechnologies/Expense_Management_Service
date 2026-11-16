package com.expense_management_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Answers 503 with {@code Retry-After} when UMS cannot be reached to validate a token.
 *
 * <p>The failure is raised inside the security filter chain, which is outside the MVC layer, so a controller advice
 * cannot see it. This filter runs before the security chain and maps only {@link UmsUnavailableException}. Everything
 * else passes through unchanged. A 401 would be wrong here, since the caller's token may be valid. The client would
 * treat it as an expired session.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UmsUnavailableFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            if (!isUmsUnavailable(e) || response.isCommitted()) {
                throw e;
            }
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", String.valueOf(ResilientJwtDecoder.DEFAULT_RETRY_AFTER.toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"The sign-in service is temporarily unavailable. Try again shortly.\"}");
        }
    }

    private static boolean isUmsUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UmsUnavailableException) {
                return true;
            }
        }
        return false;
    }
}
