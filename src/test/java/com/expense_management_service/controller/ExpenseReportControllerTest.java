package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ExpenseReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for {@link ExpenseReportController}: verifies the REST contract and
 * coarse RBAC (401/403/200/201/204). Ownership/status-gating live in the service layer and
 * are covered by {@code ExpenseReportServiceImplTest}, not here.
 */
@WebMvcTest(ExpenseReportController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExpenseReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ExpenseReportService expenseReportService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ExpenseReportResponse sampleResponse(UUID id) {
        return new ExpenseReportResponse(id, "EXP-2026-ABCD1234", "5100014", "Client visit - Q1",
                "Client visit to discuss renewal terms", "2026", UUID.randomUUID(), "Backend Development",
                "DRAFT", UUID.randomUUID(), "USD", BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
                LocalDateTime.now(), LocalDateTime.now(), 0, true, true);
    }

    private static ExpenseReportRequest sampleRequest() {
        return new ExpenseReportRequest("Client visit - Q1", "Client visit to discuss renewal terms",
                UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void create_returns201_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();
        when(expenseReportService.create(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/employee/expense-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reportStatus").value("DRAFT"));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        mockMvc.perform(post("/xms/employee/expense-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns403_forManager() throws Exception {
        mockMvc.perform(post("/xms/employee/expense-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/employee/expense-reports")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_returns400_whenBusinessPurposeMissing() throws Exception {
        String invalidBody = """
                {"title":"Client visit","businessPurpose":"","costCenterId":"%s","currencyId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/xms/employee/expense-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_returns200_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();
        when(expenseReportService.update(eq(id), any())).thenReturn(sampleResponse(id));

        mockMvc.perform(put("/xms/employee/expense-reports/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returns200_forFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(expenseReportService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/employee/expense-reports/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(id.toString()));
    }

    @Test
    void getAll_returns200_forEmployee() throws Exception {
        when(expenseReportService.getAll()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/employee/expense-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reportStatus").value("DRAFT"));
    }

    @Test
    void delete_returns204_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/expense-reports/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/expense-reports/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns403_forFinance() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/expense-reports/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }
}
