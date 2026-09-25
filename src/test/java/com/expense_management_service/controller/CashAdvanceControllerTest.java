package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CashAdvanceService;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CashAdvanceController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceService cashAdvanceService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID advanceId;
    private UUID currencyId;

    @BeforeEach
    void setUp() {
        advanceId = UUID.randomUUID();
        currencyId = UUID.randomUUID();
    }

    @Test
    void submit_returns200_andSubmitsAdvanceForApproval() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "SUBMITTED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        when(cashAdvanceService.submit(advanceId)).thenReturn(response);

        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/submit", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.managerId").value("MGR001"));
    }

    @Test
    void getMyApprovals_returns200_forManager() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "SUBMITTED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        when(currentUserService.getEmployeeId()).thenReturn("MGR001");
        when(cashAdvanceService.getMyApprovals("MGR001", null)).thenReturn(List.of(response));

        mockMvc.perform(get("/xms/employee/cash-advances/my-approvals")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].advanceId").value(advanceId.toString()))
                .andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
    }

    @Test
    void approve_returns200_forManager() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "APPROVED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        when(currentUserService.getEmployeeId()).thenReturn("MGR001");
        when(cashAdvanceService.approve(advanceId, "MGR001")).thenReturn(response);

        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/approve", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void reject_returns200_forManagerWithReason() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "REJECTED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        when(currentUserService.getEmployeeId()).thenReturn("MGR001");
        when(cashAdvanceService.reject(advanceId, "MGR001", "Exceeds budget")).thenReturn(response);

        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/reject", advanceId)
                        .param("reason", "Exceeds budget")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    void disburse_returns200_forFinanceRole() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "DISBURSED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        when(currentUserService.getEmployeeId()).thenReturn("FIN001");
        when(cashAdvanceService.disburse(advanceId, "FIN001")).thenReturn(response);

        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/disburse", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DISBURSED"));
    }

    @Test
    void disburse_returns403_forGeneralUser() throws Exception {
        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/disburse", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }
}
