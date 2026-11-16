package com.expense_management_service.security;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.config.JwtDecoderConfig;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.controller.CashAdvanceCarryForwardController;
import com.expense_management_service.dto.response.CarryForwardReadyResponse;
import com.expense_management_service.service.impl.CashAdvanceCarryForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.FINANCE;
import static com.expense_management_service.security.RoleConstants.MANAGER;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end JWT authentication through the real security chain, with the real decoder and a local UMS stand-in.
 * Covers valid, expired, forged and missing tokens, and checks that every request reuses one decoder.
 */
@WebMvcTest(CashAdvanceCarryForwardController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, JwtDecoderConfig.class, UmsUnavailableFilter.class})
class JwtAuthenticationWebTest {

    @DynamicPropertySource
    static void umsIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> UmsStub.ISSUER);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceCarryForwardService carryForwardService;

    @MockitoBean
    private CurrentUserService currentUserService;

    private static String url(UUID advanceId) {
        return "/xms/cash-advance-carry-forwards/ready/" + advanceId;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void validSignedToken_isAuthenticated_andReachesTheController() throws Exception {
        UUID advanceId = UUID.randomUUID();
        when(carryForwardService.readyFor(advanceId))
                .thenReturn(new CarryForwardReadyResponse(advanceId, "INR", null, BigDecimal.ZERO));
        String token = UmsStub.token(UmsStub.KEY, Instant.now().plusSeconds(600), List.of(FINANCE));

        mockMvc.perform(get(url(advanceId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        verify(carryForwardService).readyFor(advanceId);
    }

    @Test
    void expiredToken_isRejectedWith401_andTheServiceIsNotReached() throws Exception {
        UUID advanceId = UUID.randomUUID();
        String expired = UmsStub.token(UmsStub.KEY, Instant.now().minusSeconds(600), List.of(FINANCE));

        mockMvc.perform(get(url(advanceId)).header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(carryForwardService);
    }

    @Test
    void tokenSignedByAnotherKey_isRejectedWith401() throws Exception {
        UUID advanceId = UUID.randomUUID();
        String forged = UmsStub.token(UmsStub.forgerKey(), Instant.now().plusSeconds(600), List.of(FINANCE));

        mockMvc.perform(get(url(advanceId)).header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(carryForwardService);
    }

    @Test
    void requestWithoutAToken_isRejectedWith401() throws Exception {
        mockMvc.perform(get(url(UUID.randomUUID()))).andExpect(status().isUnauthorized());

        verifyNoInteractions(carryForwardService);
    }

    @Test
    void authenticatedButNotAuthorised_isRejectedWith403() throws Exception {
        UUID advanceId = UUID.randomUUID();
        // A valid token whose role the endpoint does not allow: authentication passes, authorisation refuses.
        String token = UmsStub.token(UmsStub.KEY, Instant.now().plusSeconds(600), List.of(MANAGER));

        mockMvc.perform(get(url(advanceId)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(carryForwardService);
    }

    @Test
    void repeatedAuthenticatedRequests_reuseOneDecoder_andDiscoveryRunsOnce() throws Exception {
        UUID advanceId = UUID.randomUUID();
        when(carryForwardService.readyFor(advanceId))
                .thenReturn(new CarryForwardReadyResponse(advanceId, "INR", null, BigDecimal.ZERO));
        String token = UmsStub.token(UmsStub.KEY, Instant.now().plusSeconds(600), List.of(FINANCE));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(url(advanceId)).header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }

        // The stub counts discovery calls across this JVM's test context; one singleton decoder means exactly one.
        org.junit.jupiter.api.Assertions.assertEquals(1, UmsStub.DISCOVERY_HITS.get(),
                "discovery must run once for the decoder, not once per request");
        assertTrue(UmsStub.JWKS_HITS.get() <= 1, "the key set is cached, not refetched per request");
        verify(carryForwardService, org.mockito.Mockito.times(5)).readyFor(advanceId);
    }
}
