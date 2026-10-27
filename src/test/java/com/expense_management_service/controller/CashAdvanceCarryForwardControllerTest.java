package com.expense_management_service.controller;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.impl.CashAdvanceCarryForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CashAdvanceCarryForwardController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceCarryForwardControllerTest {

    private static final String BASE = "/xms/cash-advance-carry-forwards";
    private static final String PROPOSE_JSON = """
            {"sourceAdvanceId":"%s","destinationAdvanceId":"%s","amount":300.00,"reason":"Trip cancelled, balance to next trip"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID());
    private static final String DECISION_JSON = "{\"reason\":\"Reviewed against the ledger\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceCarryForwardService carryForwardService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void generalUser_mayPropose() throws Exception {
        when(carryForwardService.propose(any())).thenThrow(
                new BusinessRuleViolationException("Carry-forward is disabled until Finance confirms the review-cycle rules."));

        // The role check passes; the disabled feature is then refused by the service with 422.
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(PROPOSE_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled until Finance")));
    }

    @Test
    void generalUser_cannotApprove_andTheServiceIsNotReached() throws Exception {
        mockMvc.perform(post(BASE + "/{id}/approve", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(carryForwardService);
    }

    @Test
    void generalUser_cannotReverse_orReject() throws Exception {
        mockMvc.perform(post(BASE + "/{id}/reverse", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/{id}/reject", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(carryForwardService);
    }

    @Test
    void finance_approveReachesTheService_withTheReason() throws Exception {
        UUID id = UUID.randomUUID();
        when(carryForwardService.approve(any(), any())).thenThrow(
                new BusinessRuleViolationException("The requester cannot approve their own carry-forward."));

        mockMvc.perform(post(BASE + "/{id}/approve", id).contentType(MediaType.APPLICATION_JSON)
                        .content(DECISION_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("own carry-forward")));
    }
}
