package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.impl.CashAdvanceBalanceCalculator;
import com.expense_management_service.service.impl.CashAdvanceBalanceDriftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Finance-only, read-only drift endpoint: who may call it, and what it returns (advance id, stored, calculated, difference). */
@WebMvcTest(CashAdvanceBalanceDriftController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceBalanceDriftControllerTest {

    private static final String PATH = "/xms/finance/cash-advance-balances/drift";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceBalanceDriftService driftService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void employee_isForbidden_andTheServiceIsNotCalled() throws Exception {
        mockMvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(driftService);
    }

    @Test
    void manager_isForbidden() throws Exception {
        mockMvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(driftService);
    }

    @Test
    void finance_getsEachMismatch_withAdvanceIdStoredCalculatedAndDifference() throws Exception {
        UUID advanceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(driftService.findDrift()).thenReturn(List.of(new CashAdvanceBalanceCalculator.Drift(
                advanceId, "EMP001", new BigDecimal("900.00"), new BigDecimal("700.00"), new BigDecimal("200.00"))));

        mockMvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].advanceId").value(advanceId.toString()))
                .andExpect(jsonPath("$.data[0].stored").value(900.00))
                .andExpect(jsonPath("$.data[0].computed").value(700.00))
                .andExpect(jsonPath("$.data[0].difference").value(200.00));
    }

    @Test
    void finance_withNoMismatch_getsAnEmptyList() throws Exception {
        when(driftService.findDrift()).thenReturn(List.of());

        mockMvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void admin_isAllowed() throws Exception {
        when(driftService.findDrift()).thenReturn(List.of());

        mockMvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isOk());
    }
}
