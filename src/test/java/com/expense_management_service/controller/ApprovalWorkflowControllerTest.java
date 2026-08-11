package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Action endpoints carry no role restriction (§1.5) - authorization is the per-task
 * resolved-approver/delegate check enforced inside the service, not a URL-level role gate. So these
 * tests confirm 200/401 only; there is no 403 case to test at this layer.
 */
@WebMvcTest(ApprovalWorkflowController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ApprovalWorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ApprovalWorkflowService approvalWorkflowService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private ExpenseReportResponse sampleReportResponse(String status) {
        return new ExpenseReportResponse(UUID.randomUUID(), "EXP-0001", "5100001", "Trip", "Client visit", "2026",
                UUID.randomUUID(), "Engineering", status, UUID.randomUUID(), "INR", new BigDecimal("1000"), new BigDecimal("1000"),
                LocalDateTime.now(), null, null, LocalDateTime.now(), LocalDateTime.now(), 1, true, false, 0, 0);
    }

    @Test
    void submit_returns200_forAnyAuthenticatedEmployee() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(approvalWorkflowService.submit(reportId)).thenReturn(sampleReportResponse("PENDING_APPROVAL"));

        mockMvc.perform(post("/xms/approvals/{reportId}/submit", reportId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PENDING_APPROVAL"));
    }

    @Test
    void submit_returns401_whenUnauthenticated() throws Exception {
        UUID reportId = UUID.randomUUID();

        mockMvc.perform(post("/xms/approvals/{reportId}/submit", reportId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reviewLineItem_returns200_andForwardsCurrentUsersEmployeeId() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("5100002");
        when(approvalWorkflowService.reviewLineItem(eq(reportId), eq(lineItemId), eq("5100002"), any()))
                .thenReturn(sampleReportResponse("APPROVED"));

        mockMvc.perform(post("/xms/approvals/{reportId}/line-items/{lineItemId}/review", reportId, lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("APPROVED"));
    }

    @Test
    void rejectReport_returns200() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("5100002");
        when(approvalWorkflowService.rejectReport(eq(reportId), eq("5100002"), any())).thenReturn(sampleReportResponse("REJECTED"));

        mockMvc.perform(post("/xms/approvals/{reportId}/reject", reportId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RejectReportRequest("Duplicate submission"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("REJECTED"));
    }

    @Test
    void getMyQueue_returns200() throws Exception {
        when(currentUserService.getEmployeeId()).thenReturn("5100002");
        ApprovalQueueItemResponse item =
                new ApprovalQueueItemResponse(UUID.randomUUID(), "EXP-0001", "5100001", new BigDecimal("1000"), "INR", 1, List.of(), true);
        when(approvalWorkflowService.getMyQueue(eq("5100002"), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/xms/approvals/my-queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportNumber").value("EXP-0001"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
