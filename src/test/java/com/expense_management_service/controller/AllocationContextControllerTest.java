package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.AllocationContextResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CrossDepartmentAllocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AllocationContextController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class AllocationContextControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CrossDepartmentAllocationService crossDepartmentAllocationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returnsTheCallersHomeDepartment() throws Exception {
        when(crossDepartmentAllocationService.getAllocationContextForCurrentUser())
                .thenReturn(new AllocationContextResponse("dept-eng"));

        mockMvc.perform(get("/xms/employee/allocation-context")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.homeDepartmentUuid").value("dept-eng"));
    }

    @Test
    void returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/xms/employee/allocation-context"))
                .andExpect(status().isUnauthorized());
    }
}
