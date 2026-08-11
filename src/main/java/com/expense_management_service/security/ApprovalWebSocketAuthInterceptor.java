package com.expense_management_service.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.expense_management_service.security.SecurityConstants.CLAIM_EMPLOYEE_ID;
import static com.expense_management_service.security.SecurityConstants.CLAIM_PERMISSIONS;
import static com.expense_management_service.security.SecurityConstants.CLAIM_ROLES;
import static com.expense_management_service.security.SecurityConstants.ROLE_PREFIX;

/**
 * Same-shape copy of the Leave Management Service's proven {@code AuthChannelInterceptor} (§14),
 * adapted to XMS's own JWT claim names and identity model - the STOMP principal's name is the
 * {@code employee_id} claim (not email/subject, unlike {@link JwtAuthConverter}'s HTTP-side
 * principal), since that is the exact identity {@code SimpMessagingTemplate.convertAndSendToUser}
 * is targeted with elsewhere in this package.
 * <p>
 * No role-gated broadcast topics exist in this design (every push is a per-user {@code /queue/*}
 * send from the server - see {@code ApprovalWebSocketEventListener}), so unlike its LMS counterpart
 * this interceptor only needs the three universal guards: CONNECT requires a valid JWT, SUBSCRIBE is
 * restricted to the caller's own {@code /user/queue/*}, and client SEND is restricted to
 * {@code /app/*} (server-initiated sends never go through SEND at all).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalWebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            assertOwnPersonalQueue(accessor);
        }

        if (StompCommand.SEND.equals(accessor.getCommand())) {
            assertApplicationDestination(accessor);
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String token = resolveToken(accessor);
        if (token == null) {
            throw new IllegalArgumentException("Missing JWT token in WebSocket CONNECT");
        }
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String employeeId = jwt.getClaimAsString(CLAIM_EMPLOYEE_ID);
            if (employeeId == null) {
                throw new IllegalArgumentException("JWT missing required '" + CLAIM_EMPLOYEE_ID + "' claim");
            }
            Principal principal = new UsernamePasswordAuthenticationToken(employeeId, null, extractAuthorities(jwt));
            accessor.setUser(principal);
            log.info("WS AUTH SUCCESS employeeId={}", employeeId);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("WS AUTH FAILED: {}", ex.getMessage());
            throw new IllegalArgumentException("Invalid JWT: " + ex.getMessage());
        }
    }

    /**
     * The canonical client form {@code /user/queue/X} is always safe - Spring rewrites it
     * internally to {@code /user/{principal}/queue/X}. Only an explicit
     * {@code /user/{otherEmployeeId}/queue/X} attempt (impersonating someone else) is blocked.
     */
    private void assertOwnPersonalQueue(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new IllegalArgumentException("Unauthenticated subscription attempt blocked");
        }
        String dest = accessor.getDestination();
        if (dest == null || !dest.startsWith("/user/") || dest.startsWith("/user/queue/")) {
            return;
        }
        String afterUser = dest.substring("/user/".length());
        int slash = afterUser.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String targetEmployeeId = afterUser.substring(0, slash);
        if (!targetEmployeeId.equals(principal.getName())) {
            log.warn("WS SUBSCRIBE BLOCKED employeeId={} attempted dest={}", principal.getName(), dest);
            throw new IllegalArgumentException("Not authorized to subscribe to " + dest);
        }
    }

    /**
     * Every push in this design is server-initiated ({@code SimpMessagingTemplate.
     * convertAndSendToUser}), which never travels as a client SEND frame - so a client SEND is
     * never legitimate for anything other than an {@code /app/*}-routed {@code @MessageMapping}
     * call, of which this service currently has none. Blocking everything else closes off a client
     * injecting phantom approval events straight onto the broker.
     */
    private void assertApplicationDestination(StompHeaderAccessor accessor) {
        String dest = accessor.getDestination();
        if (dest != null && !dest.startsWith("/app/")) {
            Principal principal = accessor.getUser();
            log.warn("WS SEND BLOCKED employeeId={} attempted dest={}", principal != null ? principal.getName() : "unknown", dest);
            throw new IllegalArgumentException("Client SEND is restricted to /app/ destinations. Got: " + dest);
        }
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader(SecurityConstants.AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(SecurityConstants.BEARER_PREFIX)) {
            return authHeader.substring(SecurityConstants.BEARER_PREFIX.length());
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        return attrs != null ? (String) attrs.get("token") : null;
    }

    private List<SimpleGrantedAuthority> extractAuthorities(Jwt jwt) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (roles != null) {
            roles.stream()
                    .map(role -> ROLE_PREFIX + role.toUpperCase(Locale.ROOT))
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }

        List<String> permissions = jwt.getClaimAsStringList(CLAIM_PERMISSIONS);
        if (permissions != null) {
            permissions.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        }

        return authorities;
    }
}
