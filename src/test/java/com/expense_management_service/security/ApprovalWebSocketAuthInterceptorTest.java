package com.expense_management_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApprovalWebSocketAuthInterceptorTest {

    @Mock
    private JwtDecoder jwtDecoder;

    private ApprovalWebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ApprovalWebSocketAuthInterceptor(jwtDecoder);
    }

    private Jwt jwtWithEmployeeId(String employeeId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(SecurityConstants.CLAIM_EMPLOYEE_ID, employeeId)
                .claim(SecurityConstants.CLAIM_ROLES, List.of("General"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private Message<byte[]> connectMessageWithBearerToken(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        // Mirrors what Spring's own StompSubProtocolHandler does before invoking preSend -
        // headers built via MessageBuilder are otherwise frozen, and setUser() below would throw.
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connect_setsPrincipalToEmployeeId_whenJwtIsValid() {
        when(jwtDecoder.decode("good-token")).thenReturn(jwtWithEmployeeId("5100001"));

        Message<byte[]> message = connectMessageWithBearerToken("good-token");
        interceptor.preSend(message, null);

        StompHeaderAccessor result = StompHeaderAccessor.wrap(message);
        assertThat(result.getUser().getName()).isEqualTo("5100001");
    }

    @Test
    void connect_throws_whenNoTokenPresent() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connect_fallsBackToSessionAttributeToken_whenNoAuthorizationHeader() {
        when(jwtDecoder.decode("session-token")).thenReturn(jwtWithEmployeeId("5100002"));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(Map.of("token", "session-token"));
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        StompHeaderAccessor result = StompHeaderAccessor.wrap(message);
        assertThat(result.getUser().getName()).isEqualTo("5100002");
    }

    @Test
    void connect_throws_whenJwtDecodeFails() {
        when(jwtDecoder.decode("bad-token")).thenThrow(new RuntimeException("signature mismatch"));

        assertThatThrownBy(() -> interceptor.preSend(connectMessageWithBearerToken("bad-token"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JWT");
    }

    @Test
    void connect_throws_whenEmployeeIdClaimMissing() {
        when(jwtDecoder.decode("no-employee-id")).thenReturn(Jwt.withTokenValue("token").header("alg", "none")
                .claim("sub", "someone").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());

        assertThatThrownBy(() -> interceptor.preSend(connectMessageWithBearerToken("no-employee-id"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("employee_id");
    }

    private Message<byte[]> messageAs(StompCommand command, String destination, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(principal);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void subscribe_allowsOwnCanonicalPersonalQueue() {
        Principal principal = new UsernamePasswordAuthenticationToken("5100001", null, List.of());
        Message<byte[]> message = messageAs(StompCommand.SUBSCRIBE, "/user/queue/report-updates", principal);

        assertThat(interceptor.preSend(message, null)).isNotNull();
    }

    @Test
    void subscribe_blocksImpersonatingAnotherEmployeesQueue() {
        Principal principal = new UsernamePasswordAuthenticationToken("5100001", null, List.of());
        Message<byte[]> message = messageAs(StompCommand.SUBSCRIBE, "/user/5100002/queue/report-updates", principal);

        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subscribe_blocksUnauthenticatedAttempt() {
        Message<byte[]> message = messageAs(StompCommand.SUBSCRIBE, "/user/queue/report-updates", null);

        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void send_allowsApplicationDestination() {
        Principal principal = new UsernamePasswordAuthenticationToken("5100001", null, List.of());
        Message<byte[]> message = messageAs(StompCommand.SEND, "/app/ping", principal);

        assertThat(interceptor.preSend(message, null)).isNotNull();
    }

    @Test
    void send_blocksNonApplicationDestination() {
        Principal principal = new UsernamePasswordAuthenticationToken("5100001", null, List.of());
        Message<byte[]> message = messageAs(StompCommand.SEND, "/topic/anything", principal);

        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
