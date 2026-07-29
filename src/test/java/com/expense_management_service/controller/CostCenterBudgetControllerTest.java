package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CostCenterBudgetService;
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
 * Web-layer slice test for {@link CostCenterBudgetController}: verifies the REST contract and
 * RBAC enforcement (401/403/200/201/204) without a real database — {@link CostCenterBudgetService}
 * is mocked and {@link JwtDecoder} is stubbed purely to satisfy {@link SecurityConfig}'s bean graph.
 */
@WebMvcTest(CostCenterBudgetController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CostCenterBudgetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private CostCenterBudgetService costCenterBudgetService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static CostCenterBudgetResponse sampleResponse(UUID id) {
        return new CostCenterBudgetResponse(id, UUID.randomUUID(), "Backend Development", "FY2026",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(8000), LocalDateTime.now(), LocalDateTime.now());
    }

    private static CostCenterBudgetRequest sampleRequest() {
        return new CostCenterBudgetRequest(UUID.randomUUID(), "FY2026", BigDecimal.valueOf(10000), BigDecimal.valueOf(8000));
    }

    @Test
    void create_returns201_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterBudgetService.create(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/admin/cost-center-budgets")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fiscalYear").value("FY2026"));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-center-budgets")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns403_forManager() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-center-budgets")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns403_forEmployee() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-center-budgets")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-center-budgets")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_returns200_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterBudgetService.update(eq(id), any())).thenReturn(sampleResponse(id));

        mockMvc.perform(put("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void update_returns403_forFinance() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getById_returns200_forFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterBudgetService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetId").value(id.toString()));
    }

    @Test
    void getById_returns200_forManager() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterBudgetService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returns403_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAll_returns200_forFinance() throws Exception {
        when(costCenterBudgetService.getAll()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/cost-center-budgets")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fiscalYear").value("FY2026"));
    }

    @Test
    void getAll_returns403_forEmployee() throws Exception {
        mockMvc.perform(get("/xms/admin/cost-center-budgets")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns403_forManager() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_returns403_forFinance() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/cost-center-budgets/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }
}
