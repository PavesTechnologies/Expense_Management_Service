package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.SupplementaryClaimService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks who can reach each supplementary claim endpoint. The service has its own checks as well; these tests make sure
 * the endpoint-level gate is closed for the wrong role or a missing permission.
 */
@WebMvcTest({
        EmployeeSupplementaryClaimController.class,
        ManagerSupplementaryClaimController.class,
        FinanceSupplementaryClaimController.class
})
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class SupplementaryClaimControllerTest {

    private static final String CREATE_ON_BEHALF_BODY = """
            {"reportId":"11111111-1111-1111-1111-111111111111","lateReasonCode":"RECEIPT_RECEIVED_LATE",
             "creationReason":"Employee missed it","employeeId":"EMP1"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplementaryClaimService service;

    @MockitoBean
    private com.expense_management_service.service.SupplementaryReceiptService receiptService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedCallerIsRejected() throws Exception {
        mockMvc.perform(get("/xms/employee/supplementary-claims"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCanListOwnClaims() throws Exception {
        mockMvc.perform(get("/xms/employee/supplementary-claims")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk());
    }

    @Test
    void managerQueueIsForbiddenToGeneralRole() throws Exception {
        mockMvc.perform(get("/xms/manager/supplementary-claims/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerQueueIsAllowedToManagerRole() throws Exception {
        mockMvc.perform(get("/xms/manager/supplementary-claims/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk());
    }

    @Test
    void financeQueueRefusesOtherRoles() throws Exception {
        mockMvc.perform(get("/xms/finance/supplementary-claims/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GENERAL"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeQueueIsAllowedForFinanceExecutiveRole() throws Exception {
        mockMvc.perform(get("/xms/finance/supplementary-claims/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_FINANCE_EXECUTIVE"))))
                .andExpect(status().isOk());
    }

    @Test
    void creatingOnBehalfFailsClosedWithoutCreatePermission() throws Exception {
        mockMvc.perform(post("/xms/finance/supplementary-claims")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_ON_BEHALF_BODY)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingOnBehalfIsAllowedWithRoleAndCreatePermission() throws Exception {
        mockMvc.perform(post("/xms/finance/supplementary-claims")
                        .with(csrf())
                        .contentType("application/json")
                        .content(CREATE_ON_BEHALF_BODY)
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(ROLE_FINANCE),
                                new SimpleGrantedAuthority("SUPP_CLAIM_CREATE_FOR_EMPLOYEE"))))
                .andExpect(status().isCreated());
    }
}
