package com.expense_management_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.CashAdvanceManagerDecisionRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.security.CashAdvanceReviewAccess;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CashAdvanceService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may open a cash advance and record a manager decision on it. A Reporting Manager is limited to advances
 * assigned to them; the other roles keep the rules they had before.
 */
@WebMvcTest(CashAdvanceController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceReviewAuthorizationTest {

    private static final String ROLE_RM = "ROLE_REPORTING_MANAGER";
    private static final String ROLE_EMPLOYEE_ONLY = "ROLE_GENERAL_EMPLOYEE_NOT_LISTED";
    private static final String ROLE_AP = "ROLE_AP_EXECUTIVE";
    private static final String RM_ID = "5100099";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceService cashAdvanceService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean(name = "cashAdvanceReviewAccess")
    private CashAdvanceReviewAccess cashAdvanceReviewAccess;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID advanceId;

    @BeforeEach
    void setUp() {
        advanceId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn(RM_ID);
    }

    // ------------------------------------------------------------ GET /{advanceId}

    @Test
    void assignedReportingManagerCanViewTheAdvance() throws Exception {
        when(cashAdvanceReviewAccess.isAssignedApprover(advanceId)).thenReturn(true);
        when(cashAdvanceService.getById(advanceId)).thenReturn(response("PENDING_MANAGER_APPROVAL"));

        mockMvc.perform(get("/xms/employee/cash-advances/{advanceId}", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_RM))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.advanceId").value(advanceId.toString()));
    }

    @Test
    void unassignedReportingManagerIsForbiddenFromViewing() throws Exception {
        when(cashAdvanceReviewAccess.isAssignedApprover(advanceId)).thenReturn(false);

        mockMvc.perform(get("/xms/employee/cash-advances/{advanceId}", advanceId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_RM))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cashAdvanceService);
    }

    @Test
    void existingViewRolesKeepAccessWithoutTheAssignmentCheck() throws Exception {
        when(cashAdvanceService.getById(advanceId)).thenReturn(response("PENDING_MANAGER_APPROVAL"));

        for (String role : new String[] {"ROLE_ADMIN", "ROLE_GENERAL", "ROLE_FINANCE", "ROLE_FINANCE_EXECUTIVE", "ROLE_MANAGER"}) {
            mockMvc.perform(get("/xms/employee/cash-advances/{advanceId}", advanceId)
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk());
        }
        verifyNoInteractions(cashAdvanceReviewAccess);
    }

    @Test
    void rolesOutsideTheReviewRulesAreBlockedFromViewing() throws Exception {
        for (String role : new String[] {ROLE_AP, ROLE_EMPLOYEE_ONLY}) {
            mockMvc.perform(get("/xms/employee/cash-advances/{advanceId}", advanceId)
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(cashAdvanceService);
    }

    // ------------------------------------------------------------ POST /{advanceId}/manager-decision

    @Test
    void assignedReportingManagerCanApprove() throws Exception {
        when(cashAdvanceReviewAccess.isAssignedApprover(advanceId)).thenReturn(true);
        when(cashAdvanceService.managerDecision(eq(advanceId), eq(RM_ID), any()))
                .thenReturn(response("APPROVED"));

        decide("APPROVE_FULL", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        verify(cashAdvanceService).managerDecision(eq(advanceId), eq(RM_ID), any(CashAdvanceManagerDecisionRequest.class));
    }

    @Test
    void assignedReportingManagerCanReject() throws Exception {
        when(cashAdvanceReviewAccess.isAssignedApprover(advanceId)).thenReturn(true);
        when(cashAdvanceService.managerDecision(eq(advanceId), eq(RM_ID), any()))
                .thenReturn(response("REJECTED"));

        decide("REJECT", "Outside policy")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    void assignedReportingManagerCanDefer() throws Exception {
        when(cashAdvanceReviewAccess.isAssignedApprover(advanceId)).thenReturn(true);
        when(cashAdvanceService.managerDecision(eq(advanceId), eq(RM_ID), any()))
                .thenReturn(response("DEFERRED"));

        decide("DEFER", "Resubmit next month")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEFERRED"));
    }

    @Test
    void unassignedReportingManagerIsForbiddenFromDeciding() throws Exception {
        when(cashAdvanceReviewAccess.isAssignedApprover(advanceId)).thenReturn(false);

        decide("APPROVE_FULL", null).andExpect(status().isForbidden());

        verifyNoInteractions(cashAdvanceService);
    }

    @Test
    void existingDecisionRolesKeepAccess() throws Exception {
        when(cashAdvanceService.managerDecision(eq(advanceId), eq(RM_ID), any()))
                .thenReturn(response("APPROVED"));

        for (String role : new String[] {"ROLE_ADMIN", "ROLE_GENERAL", "ROLE_FINANCE", "ROLE_FINANCE_EXECUTIVE", "ROLE_MANAGER"}) {
            mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/manager-decision", advanceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"APPROVE_FULL\"}")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk());
        }
        verifyNoInteractions(cashAdvanceReviewAccess);
    }

    @Test
    void rolesOutsideTheDecisionRulesAreBlocked() throws Exception {
        decideAs(ROLE_AP, "APPROVE_FULL", null).andExpect(status().isForbidden());
        decideAs(ROLE_EMPLOYEE_ONLY, "APPROVE_FULL", null).andExpect(status().isForbidden());

        verifyNoInteractions(cashAdvanceService);
    }

    // ------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions decide(String decision, String justification)
            throws Exception {
        return decideAs(ROLE_RM, decision, justification);
    }

    private org.springframework.test.web.servlet.ResultActions decideAs(String role, String decision, String justification)
            throws Exception {
        String body = "{\"decision\":\"" + decision + "\"" + (justification == null ? "" : ",\"justification\":\"" + justification + "\"") + "}";
        return mockMvc.perform(post("/xms/employee/cash-advances/{advanceId}/manager-decision", advanceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(jwt().authorities(new SimpleGrantedAuthority(role))));
    }

    private CashAdvanceResponse response(String status) {
        return new CashAdvanceResponse(advanceId, "5100014", RM_ID, new BigDecimal("1000.00"), UUID.randomUUID(), "INR",
                new BigDecimal("1000.00"), "Travel", status, null, null, BigDecimal.ZERO, null, null);
    }
}
