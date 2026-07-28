package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.PolicyViolationService;
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

import static com.expense_management_service.security.RoleConstants.ROLE_EMPLOYEE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer slice test for {@link PolicyViolationController}. Ownership/editability gating live in the service layer. */
@WebMvcTest(PolicyViolationController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class PolicyViolationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private PolicyViolationService policyViolationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static PolicyWarningResponse sampleWarning() {
        return new PolicyWarningResponse(UUID.randomUUID(), PolicyRuleType.MISSING_DESCRIPTION, PolicySeverity.WARN,
                "This expense is missing a description", null, null);
    }

    @Test
    void getAll_returns200_forEmployee() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(policyViolationService.getForLineItem(reportId, lineItemId)).thenReturn(List.of(sampleWarning()));

        mockMvc.perform(get("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}/policy-warnings", reportId, lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_EMPLOYEE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].message").value("This expense is missing a description"));
    }

    @Test
    void getAll_returns401_whenUnauthenticated() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();

        mockMvc.perform(get("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}/policy-warnings", reportId, lineItemId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void justify_returns200_forEmployee() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        UUID violationId = UUID.randomUUID();
        PolicyWarningResponse justified = new PolicyWarningResponse(violationId, PolicyRuleType.MISSING_DESCRIPTION,
                PolicySeverity.WARN, "This expense is missing a description", "Client requested no memo", LocalDateTime.now());
        when(policyViolationService.justify(eq(reportId), eq(lineItemId), eq(violationId), any())).thenReturn(justified);

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}/policy-warnings/{violationId}/justify",
                        reportId, lineItemId, violationId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_EMPLOYEE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PolicyJustificationRequest("Client requested no itemised memo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.justification").value("Client requested no memo"));
    }

    @Test
    void justify_returns403_forFinance() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        UUID violationId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}/policy-warnings/{violationId}/justify",
                        reportId, lineItemId, violationId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PolicyJustificationRequest("Client requested no itemised memo"))))
                .andExpect(status().isForbidden());
    }
}
