package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ExpenseCategoryService;
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

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseCategoryController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExpenseCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ExpenseCategoryService expenseCategoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ExpenseCategoryResponse sampleResponse(UUID id) {
        return new ExpenseCategoryResponse(id, "TRAVEL", "Travel", UUID.randomUUID(), "Travel Expense",
                "desc", true, null, "TX01", LocalDate.of(2026, 1, 1), null, "ACTIVE",
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static ExpenseCategoryRequest sampleRequest() {
        return new ExpenseCategoryRequest("TRAVEL", "Travel", UUID.randomUUID(), "desc", true,
                null, "TX01", LocalDate.of(2026, 1, 1), null, "ACTIVE");
    }

    @Test
    void create_returns201_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(expenseCategoryService.create(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/admin/expense-categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryName").value("Travel"));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        mockMvc.perform(post("/xms/admin/expense-categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/admin/expense-categories")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_returns200_forFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(expenseCategoryService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/expense-categories/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(id.toString()));
    }

    @Test
    void getById_returns403_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/xms/admin/expense-categories/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getActive_returns200_forManager() throws Exception {
        when(expenseCategoryService.getActiveCategories()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/expense-categories/active")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/expense-categories/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns403_forManager() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/expense-categories/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
    }
}
