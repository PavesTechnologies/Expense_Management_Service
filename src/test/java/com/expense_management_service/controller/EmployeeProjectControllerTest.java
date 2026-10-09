package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.AssignedProjectResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.EmployeeProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for {@link EmployeeProjectController} (Epic 8 project selection).
 * PMS/RMS resolution logic itself is covered by {@code EmployeeProjectServiceImpl} and
 * {@code ExpenseLineItemServiceImplTest}, not here — this only verifies the REST contract and RBAC.
 */
@WebMvcTest(EmployeeProjectController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class EmployeeProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeProjectService employeeProjectService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getAssignedProjects_returns200_forGeneralEmployee() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(employeeProjectService.getAssignedProjects())
                .thenReturn(List.of(new AssignedProjectResponse(projectId, "ALPHA", "Project Alpha", "ACTIVE")));

        mockMvc.perform(get("/xms/employee/projects/assigned")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].projectCode").value("ALPHA"));
    }

    @Test
    void getAssignedProjects_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/xms/employee/projects/assigned"))
                .andExpect(status().isUnauthorized());
    }

}
