package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.impl.CashAdvanceReconciliationService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CashAdvanceReconciliationController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceReconciliationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceReconciliationService reconciliationService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void generalUser_cannotRunTheSnapshot_orCreateOrCloseARun() throws Exception {
        mockMvc.perform(get("/xms/finance/reconciliation/snapshot")
                        .param("periodType", "MONTHLY").param("anchorDate", "2026-10-23")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/xms/finance/reconciliation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodType\":\"MONTHLY\",\"anchorDate\":\"2026-10-23\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/xms/finance/reconciliation/runs/{id}/close", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void finance_reachesTheService_forTheSnapshot() throws Exception {
        mockMvc.perform(get("/xms/finance/reconciliation/snapshot")
                        .param("periodType", "MONTHLY").param("anchorDate", "2026-10-23")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk());
    }
}
