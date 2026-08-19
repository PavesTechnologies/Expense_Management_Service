package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.FinanceQueryRequest;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.FinanceQueueItemResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.FinanceVerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-layer authorization: Layer 1 ({@code @PreAuthorize("hasRole('FINANCE_EXECUTIVE')")} on this
 * controller, role-only - matches this module's existing role-based, not permission-based,
 * convention) is tested here. Layer 2 (the resolved-approver-or-delegate check inside {@code
 * FinanceVerificationServiceImpl}) is exercised for real in {@code FinanceVerificationServiceImplTest};
 * here it's simulated by mocking the service to succeed (assigned/delegate) or throw {@code
 * AccessDeniedException} (not assigned) - this class only proves the controller correctly gates
 * Layer 1 and correctly surfaces Layer 2's outcome, since the service itself is mocked in a
 * {@code @WebMvcTest} slice.
 */
@WebMvcTest(FinanceVerificationController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class FinanceVerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private FinanceVerificationService financeVerificationService;

    @MockitoBean
    private ApprovalWorkflowService approvalWorkflowService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor financeExecutive() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE_EXECUTIVE));
    }

    private static RequestPostProcessor general() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL));
    }

    private static RequestPostProcessor reportingManager() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER));
    }

    private ExpenseReportResponse sampleReportResponse(String status) {
        return new ExpenseReportResponse(UUID.randomUUID(), "EXP-0001", "5100001", "Trip", "Client visit", "2026",
                UUID.randomUUID(), "Engineering", status, UUID.randomUUID(), "INR", new BigDecimal("1000"), new BigDecimal("1000"),
                LocalDateTime.now(), null, null, LocalDateTime.now(), LocalDateTime.now(), 1, true, false, 0, 0);
    }

    // ---------------------------------------------------------------------
    // Layer 1 - role gate
    // ---------------------------------------------------------------------

    @Test
    void verifyLineItem_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/verify", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyLineItem_returns403_forGeneralRole() throws Exception {
        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/verify", UUID.randomUUID(), UUID.randomUUID())
                        .with(general()))
                .andExpect(status().isForbidden());
        verify(financeVerificationService, never()).verifyLineItem(any(), any(), any());
    }

    @Test
    void verifyLineItem_returns403_forReportingManagerRole() throws Exception {
        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/verify", UUID.randomUUID(), UUID.randomUUID())
                        .with(reportingManager()))
                .andExpect(status().isForbidden());
        verify(financeVerificationService, never()).verifyLineItem(any(), any(), any());
    }

    @Test
    void getMyQueue_returns403_forGeneralRole() throws Exception {
        mockMvc.perform(get("/xms/finance-verification/my-queue").with(general()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getMyQueue_returns403_forReportingManagerRole() throws Exception {
        mockMvc.perform(get("/xms/finance-verification/my-queue").with(reportingManager()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStatus_returns403_forGeneralRole() throws Exception {
        mockMvc.perform(get("/xms/finance-verification/{reportId}/status", UUID.randomUUID()).with(general()))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryLineItem_returns403_forGeneralRole() throws Exception {
        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/query", UUID.randomUUID(), UUID.randomUUID())
                        .with(general())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new FinanceQueryRequest("reason"))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // FINANCE_EXECUTIVE passes Layer 1 - Layer 2 (assignment/delegation) decides the outcome
    // ---------------------------------------------------------------------

    @Test
    void verifyLineItem_returns403_whenFinanceExecutiveIsNotTheAssignedApprover() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("5100099");
        when(financeVerificationService.verifyLineItem(reportId, lineItemId, "5100099"))
                .thenThrow(new AccessDeniedException("You are not an active Finance approver (or delegate) for this report's current level"));

        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/verify", reportId, lineItemId)
                        .with(financeExecutive()))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifyLineItem_returns200_whenFinanceExecutiveIsTheAssignedApprover() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("5100050");
        when(financeVerificationService.verifyLineItem(reportId, lineItemId, "5100050"))
                .thenReturn(sampleReportResponse("PENDING_FINANCE_VERIFICATION"));

        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/verify", reportId, lineItemId)
                        .with(financeExecutive()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PENDING_FINANCE_VERIFICATION"));
    }

    @Test
    void verifyLineItem_returns200_whenFinanceExecutiveIsAnActiveDelegate() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        // The caller isn't the resolved approver themselves, but FinanceVerificationServiceImpl's
        // own delegation check (unchanged, exercised for real in FinanceVerificationServiceImplTest)
        // resolves them as the approver's active delegate and succeeds - the controller just
        // surfaces whatever Layer 2 decided.
        when(currentUserService.getEmployeeId()).thenReturn("5100077");
        when(financeVerificationService.verifyLineItem(reportId, lineItemId, "5100077"))
                .thenReturn(sampleReportResponse("PENDING_FINANCE_VERIFICATION"));

        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/verify", reportId, lineItemId)
                        .with(financeExecutive()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PENDING_FINANCE_VERIFICATION"));
    }

    @Test
    void queryLineItem_returns200_forFinanceExecutive() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("5100050");
        when(financeVerificationService.queryLineItem(eq(reportId), eq(lineItemId), eq("5100050"), eq("Missing itemized bill")))
                .thenReturn(sampleReportResponse("AWAITING_CORRECTION"));

        mockMvc.perform(post("/xms/finance-verification/{reportId}/line-items/{lineItemId}/query", reportId, lineItemId)
                        .with(financeExecutive())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new FinanceQueryRequest("Missing itemized bill"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("AWAITING_CORRECTION"));
    }

    @Test
    void getMyQueue_returns200_forFinanceExecutive() throws Exception {
        when(currentUserService.getEmployeeId()).thenReturn("5100050");
        FinanceQueueItemResponse item = new FinanceQueueItemResponse(
                UUID.randomUUID(), "EXP-0001", "5100001", new BigDecimal("1000"), "INR", "Engineering", 2, List.of());
        when(financeVerificationService.getFinanceQueue(eq("5100050"), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/xms/finance-verification/my-queue")
                        .with(financeExecutive()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportNumber").value("EXP-0001"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getStatus_returns200_forFinanceExecutive() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(approvalWorkflowService.getApprovalStatus(reportId))
                .thenReturn(new ApprovalStatusResponse(2, "Finance Verification", "Finance Verification", 2, false, true));

        mockMvc.perform(get("/xms/finance-verification/{reportId}/status", reportId)
                        .with(financeExecutive()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentLevelOrder").value(2))
                .andExpect(jsonPath("$.data.canRecall").value(false));
    }
}
