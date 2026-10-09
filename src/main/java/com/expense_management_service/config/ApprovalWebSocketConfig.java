package com.expense_management_service.config;

import com.expense_management_service.security.ApprovalWebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Map;

/**
 * STOMP-over-WebSocket for live approval updates (§14) - a same-shape, independently-run copy of
 * the Leave Management Service's proven {@code WebSocketConfig} (a separate Spring Boot process on
 * its own port; its in-JVM {@code SimpleBroker} cannot be shared across services). Endpoint path is
 * {@code /xms/ws}, matching this service's own convention of baking the {@code /xms} prefix directly
 * into every mapping rather than relying on a gateway to strip it.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class ApprovalWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ApprovalWebSocketAuthInterceptor approvalWebSocketAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/xms/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HttpSessionHandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        // SockJS sends the token as a query param during the HTTP handshake, before
                        // any STOMP frame (and its headers) exists - stashed here so
                        // ApprovalWebSocketAuthInterceptor can read it back on CONNECT.
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            String token = servletRequest.getServletRequest().getParameter("token");
                            if (token != null) {
                                attributes.put("token", token);
                            }
                        }
                        return true;
                    }
                })
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(approvalWebSocketAuthInterceptor);
    }
}
