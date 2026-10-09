package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.DepartmentApproverService;
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
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DepartmentApproverController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class DepartmentApproverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private DepartmentApproverService departmentApproverService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void create_returns201_forAdmin() throws Exception {
        UUID departmentUuid = UUID.randomUUID();
        DepartmentApproverRequest request = new DepartmentApproverRequest(departmentUuid, "5100014", "ACTIVE");
        when(departmentApproverService.create(any())).thenReturn(
                new DepartmentApproverResponse(UUID.randomUUID(), departmentUuid, "5100014", "ACTIVE", LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/xms/admin/department-approvers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.approverEmployeeId").value("5100014"));
    }

    @Test
    void create_returns403_forGeneralEmployee() throws Exception {
        DepartmentApproverRequest request = new DepartmentApproverRequest(UUID.randomUUID(), "5100014", "ACTIVE");

        mockMvc.perform(post("/xms/admin/department-approvers")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        DepartmentApproverRequest request = new DepartmentApproverRequest(UUID.randomUUID(), "5100014", "ACTIVE");

        mockMvc.perform(post("/xms/admin/department-approvers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
