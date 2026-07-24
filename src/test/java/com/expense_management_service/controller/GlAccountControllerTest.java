package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.GlAccountService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_EMPLOYEE;
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

/**
 * Web-layer slice test for {@link GlAccountController}: verifies the REST contract and
 * RBAC enforcement (401/403/200/201/204) without a real database or UMS connection —
 * {@link GlAccountService} is mocked and {@link JwtDecoder} is stubbed purely to satisfy
 * {@link SecurityConfig}'s bean graph, since {@code jwt()} test support injects the
 * authentication directly and never invokes it.
 */
@WebMvcTest(GlAccountController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class GlAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private GlAccountService glAccountService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static GlAccountResponse sampleResponse(UUID id) {
        return new GlAccountResponse(id, "6000", "Travel Expense", "EXPENSE", "desc", "ACTIVE",
                LocalDateTime.now(), LocalDateTime.now(), 0L);
    }

    @Test
    void create_returns201_forAdmin() throws Exception {
        GlAccountRequest request = new GlAccountRequest("6000", "Travel Expense", "EXPENSE", "desc", "ACTIVE");
        UUID id = UUID.randomUUID();
        when(glAccountService.create(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/admin/gl-accounts")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.glAccountCode").value("6000"));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        GlAccountRequest request = new GlAccountRequest("6000", "Travel Expense", "EXPENSE", "desc", "ACTIVE");

        mockMvc.perform(post("/xms/admin/gl-accounts")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        GlAccountRequest request = new GlAccountRequest("6000", "Travel Expense", "EXPENSE", "desc", "ACTIVE");

        mockMvc.perform(post("/xms/admin/gl-accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_returns200_forFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(glAccountService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/gl-accounts/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.glAccountId").value(id.toString()));
    }

    @Test
    void getById_returns403_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/xms/admin/gl-accounts/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_EMPLOYEE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getActive_returns200_forManager() throws Exception {
        when(glAccountService.getActiveAccounts()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/gl-accounts/active")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/gl-accounts/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns403_forManager() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/gl-accounts/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
    }
}
