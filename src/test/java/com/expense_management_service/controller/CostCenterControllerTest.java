package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CostCenterService;
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
 * Web-layer slice test for {@link CostCenterController}: verifies the REST contract and
 * RBAC enforcement (401/403/200/201/204) without a real database, UMS, or Employee
 * Onboarding connection — {@link CostCenterService} is mocked and {@link JwtDecoder} is
 * stubbed purely to satisfy {@link SecurityConfig}'s bean graph.
 */
@WebMvcTest(CostCenterController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CostCenterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private CostCenterService costCenterService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static CostCenterResponse sampleResponse(UUID id) {
        return new CostCenterResponse(id, "CC-100", "Backend Development", UUID.randomUUID(), "desc",
                "5100014", "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    }

    private static CostCenterRequest sampleRequest() {
        return new CostCenterRequest("CC-100", "Backend Development", UUID.randomUUID(), "desc",
                "5100014", "ACTIVE");
    }

    @Test
    void create_returns201_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterService.create(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/admin/cost-centers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.costCenterCode").value("CC-100"));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-centers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns403_forManager() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-centers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns403_forEmployee() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-centers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/admin/cost-centers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_returns200_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterService.update(eq(id), any())).thenReturn(sampleResponse(id));

        mockMvc.perform(put("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void update_returns403_forFinance() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getById_returns200_forFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.costCenterId").value(id.toString()));
    }

    @Test
    void getById_returns200_forManager() throws Exception {
        UUID id = UUID.randomUUID();
        when(costCenterService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returns403_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAll_returns200_forFinance() throws Exception {
        when(costCenterService.getAll()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/cost-centers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].costCenterCode").value("CC-100"));
    }

    @Test
    void getAll_returns200_forGeneral() throws Exception {
        mockMvc.perform(get("/xms/admin/cost-centers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk());
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns403_forManager() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_returns403_forFinance() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/cost-centers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }
}
