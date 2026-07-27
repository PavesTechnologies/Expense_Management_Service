package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ExpenseReportService;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_EMPLOYEE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for the new {@code POST /submit} action endpoint (EP06 plan, Phase 2).
 * Scope is deliberately limited to this new endpoint - the pre-existing CRUD endpoints on this
 * controller have no test coverage today and backfilling them is outside this change's scope.
 */
@WebMvcTest(ExpenseReportController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExpenseReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseReportService expenseReportService;

    @MockitoBean
    private ApprovalWorkflowService approvalWorkflowService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ExpenseReportResponse sampleResponse(UUID id) {
        return new ExpenseReportResponse(id, "ER-1001", "EMP-1", "Trip", "Client visit",
                UUID.randomUUID(), "HQ", "PENDING_APPROVAL", UUID.randomUUID(), "USD",
                BigDecimal.valueOf(500), null, LocalDateTime.now(), null, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void submit_returns200_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();
        when(approvalWorkflowService.submit(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/submit", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_EMPLOYEE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PENDING_APPROVAL"));
    }

    @Test
    void submit_returns403_forFinance() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/submit", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void submit_returns401_whenUnauthenticated() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/submit", id))
                .andExpect(status().isUnauthorized());
    }
}
