package com.expense_management_service.security;

import com.expense_management_service.config.JwtDecoderConfig;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.controller.CashAdvanceCarryForwardController;
import com.expense_management_service.service.impl.CashAdvanceCarryForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens when UMS is down. The issuer points at a socket that accepts connections and never answers, so discovery
 * times out. The client must get 503 with Retry-After, not 500 and not 401, and later requests in the cooldown must fail
 * fast instead of waiting for another UMS attempt.
 */
@WebMvcTest(CashAdvanceCarryForwardController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, JwtDecoderConfig.class, UmsUnavailableFilter.class})
class JwtAuthenticationUmsDownWebTest {

    private static final ServerSocket STALLED_UMS = openStalledSocket();

    @DynamicPropertySource
    static void umsIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:" + STALLED_UMS.getLocalPort() + "/ums");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceCarryForwardService carryForwardService;

    @MockitoBean
    private CurrentUserService currentUserService;

    private static ServerSocket openStalledSocket() {
        try {
            return new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void unreachableUms_returns503WithRetryAfter_andNeverReachesTheService() throws Exception {
        UUID advanceId = UUID.randomUUID();

        mockMvc.perform(get("/xms/cash-advance-carry-forwards/ready/" + advanceId)
                        .header("Authorization", "Bearer opaque-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(carryForwardService);
    }

    @Test
    void secondRequestInsideTheCooldown_failsFastWithoutAnotherUmsAttempt() throws Exception {
        UUID advanceId = UUID.randomUUID();
        // The first test in this class (or this one, if it runs first) has already started the cooldown.
        mockMvc.perform(get("/xms/cash-advance-carry-forwards/ready/" + advanceId)
                .header("Authorization", "Bearer opaque-token")).andExpect(status().isServiceUnavailable());

        long started = System.nanoTime();
        mockMvc.perform(get("/xms/cash-advance-carry-forwards/ready/" + advanceId)
                        .header("Authorization", "Bearer opaque-token"))
                .andExpect(status().isServiceUnavailable());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // A fresh attempt would wait up to the 5 s read timeout. Inside the cooldown it must return almost at once.
        assertTrue(elapsedMs < 2_000, "the cooldown should answer without contacting UMS: " + elapsedMs + " ms");
    }

    @Test
    void anonymousRequest_isStill401_whenUmsIsDown() throws Exception {
        // No bearer token means no decoder call, so an outage must not change the answer for anonymous callers.
        mockMvc.perform(get("/xms/cash-advance-carry-forwards/ready/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
