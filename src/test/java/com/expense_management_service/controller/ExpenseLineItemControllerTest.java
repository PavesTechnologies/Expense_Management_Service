package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ExpenseLineItemService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
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
 * Web-layer slice test for {@link ExpenseLineItemController}: verifies the nested REST
 * contract and coarse RBAC. Ownership/status-gating live in the service layer and are
 * covered by {@code ExpenseLineItemServiceImplTest}, not here.
 */
@WebMvcTest(ExpenseLineItemController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExpenseLineItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ExpenseLineItemService expenseLineItemService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ExpenseLineItemResponse sampleResponse(UUID reportId, UUID lineItemId) {
        return new ExpenseLineItemResponse(lineItemId, reportId, "EXP-2026-ABCD1234", "DRAFT",
                UUID.randomUUID(), "Travel", true, true, new BigDecimal("500.00"),
                LocalDate.now().minusDays(1), "Uber", "Client meeting", new BigDecimal("100.00"),
                UUID.randomUUID(), "USD", BigDecimal.ONE, new BigDecimal("100.00"), "INR", null, new BigDecimal("100.00"),
                null, null, null, null, false, "ACTIVE", LocalDateTime.now(), LocalDateTime.now(), List.of());
    }

    private static ExpenseLineItemRequest sampleRequest() {
        return new ExpenseLineItemRequest(UUID.randomUUID(), LocalDate.now().minusDays(1), "Uber",
                "Client meeting", new BigDecimal("100.00"), UUID.randomUUID(), null, null, null, false);
    }

    @Test
    void create_returns201_forEmployee() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(expenseLineItemService.create(eq(reportId), any())).thenReturn(sampleResponse(reportId, lineItemId));

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/line-items", reportId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reportId").value(reportId.toString()));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        UUID reportId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/line-items", reportId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        UUID reportId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/line-items", reportId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_returns200_forEmployee() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(expenseLineItemService.update(eq(reportId), eq(lineItemId), any()))
                .thenReturn(sampleResponse(reportId, lineItemId));

        mockMvc.perform(put("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}", reportId, lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void getAll_returns200_forManager() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(expenseLineItemService.getAllForReport(reportId))
                .thenReturn(List.of(sampleResponse(reportId, UUID.randomUUID())));

        mockMvc.perform(get("/xms/employee/expense-reports/{reportId}/line-items", reportId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reportId").value(reportId.toString()));
    }

    @Test
    void delete_returns204_forEmployee() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}", reportId, lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}", reportId, lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }
}
