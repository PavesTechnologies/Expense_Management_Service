package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CashAdvanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    void getMyApprovals_returns200_withPaginationForManager() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "SUBMITTED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        Pageable pageable = PageRequest.of(0, 10);
        Page<CashAdvanceResponse> page = new PageImpl<>(List.of(response), pageable, 1);

        when(currentUserService.getEmployeeId()).thenReturn("MGR001");
        when(cashAdvanceService.getMyApprovals("MGR001", null, pageable)).thenReturn(page);

        mockMvc.perform(get("/xms/employee/cash-advances/my-approvals")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].advanceId").value(advanceId.toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
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
        when(cashAdvanceService.disburse(eq(advanceId), eq("FIN001"), any(CashAdvanceDisbursement.class))).thenReturn(response);

        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/disburse", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DISBURSED"));
    }

    @Test
    void disburse_passesBankTransferFieldsToService() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "IN_PROGRESS", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );
        when(currentUserService.getEmployeeId()).thenReturn("FIN001");
        when(cashAdvanceService.disburse(eq(advanceId), eq("FIN001"), any(CashAdvanceDisbursement.class))).thenReturn(response);

        mockMvc.perform(multipart("/xms/employee/cash-advances/{advanceId}/disburse", advanceId)
                        .param("disbursementMode", "BANK_TRANSFER")
                        .param("transactionReference", "UTR123")
                        .param("transferDate", "2026-10-01")
                        .param("transferDetails", "HDFC A/C 1234")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk());

        ArgumentCaptor<CashAdvanceDisbursement> captor = ArgumentCaptor.forClass(CashAdvanceDisbursement.class);
        verify(cashAdvanceService).disburse(eq(advanceId), eq("FIN001"), captor.capture());
        CashAdvanceDisbursement sent = captor.getValue();
        assertEquals("BANK_TRANSFER", sent.disbursementMode());
        assertEquals("UTR123", sent.transactionReference());
        assertEquals(LocalDate.of(2026, 10, 1), sent.transferDate());
        assertEquals("HDFC A/C 1234", sent.transferDetails());
    }

    @Test
    void disburse_returns400_whenProofIsInvalid() throws Exception {
        when(currentUserService.getEmployeeId()).thenReturn("FIN001");
        when(cashAdvanceService.disburse(eq(advanceId), eq("FIN001"), any(CashAdvanceDisbursement.class)))
                .thenThrow(new IllegalArgumentException("Enter the transaction reference."));

        mockMvc.perform(multipart("/xms/employee/cash-advances/{advanceId}/disburse", advanceId)
                        .param("disbursementMode", "BANK_TRANSFER")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter the transaction reference."));
    }

    @Test
    void disburse_returns403_forGeneralUser() throws Exception {
        mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/disburse", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getMyAdvances_returns200_withPagination() throws Exception {
        CashAdvanceResponse response1 = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "DRAFT", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );
        CashAdvanceResponse response2 = new CashAdvanceResponse(
                UUID.randomUUID(), "EMP001", "MGR001", new BigDecimal("300.00"), currencyId, "USD",
                new BigDecimal("300.00"), "Conference", "SUBMITTED", LocalDate.now().plusDays(14),
                new BigDecimal("300.00"), null, null
        );

        Pageable pageable = PageRequest.of(0, 10);
        Page<CashAdvanceResponse> page = new PageImpl<>(List.of(response1, response2), pageable, 2);

        when(currentUserService.getEmployeeId()).thenReturn("EMP001");
        when(cashAdvanceService.getMyAdvances("EMP001", null, pageable)).thenReturn(page);

        mockMvc.perform(get("/xms/employee/cash-advances/my-advances")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.content[1].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void getMyAdvances_returns200_withStatusFilter() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "SUBMITTED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        Pageable pageable = PageRequest.of(0, 10);
        Page<CashAdvanceResponse> page = new PageImpl<>(List.of(response), pageable, 1);

        when(currentUserService.getEmployeeId()).thenReturn("EMP001");
        when(cashAdvanceService.getMyAdvances("EMP001", "SUBMITTED", pageable)).thenReturn(page);

        mockMvc.perform(get("/xms/employee/cash-advances/my-advances")
                        .param("status", "SUBMITTED")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getAll_returns200_withPaginationAndFilters() throws Exception {
        CashAdvanceResponse response = new CashAdvanceResponse(
                advanceId, "EMP001", "MGR001", new BigDecimal("500.00"), currencyId, "USD",
                new BigDecimal("500.00"), "Travel", "APPROVED", LocalDate.now().plusDays(7),
                new BigDecimal("500.00"), null, null
        );

        Pageable pageable = PageRequest.of(0, 10);
        Page<CashAdvanceResponse> page = new PageImpl<>(List.of(response), pageable, 1);

        when(cashAdvanceService.getFiltered("EMP001", "APPROVED", pageable)).thenReturn(page);

        mockMvc.perform(get("/xms/employee/cash-advances")
                        .param("employeeId", "EMP001")
                        .param("status", "APPROVED")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
