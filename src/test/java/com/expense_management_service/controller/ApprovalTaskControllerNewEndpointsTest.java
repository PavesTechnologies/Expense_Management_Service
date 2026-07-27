package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.ApprovalActionRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ApprovalTaskService;
import com.expense_management_service.service.ApprovalWorkflowService;
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

import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_EMPLOYEE;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for the new approve/reject/my-queue action endpoints (EP06 plan, Phase 2).
 * Pre-existing CRUD endpoints on this controller have no test coverage today; that gap is not
 * backfilled here - only the new endpoints are covered.
 */
@WebMvcTest(ApprovalTaskController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ApprovalTaskControllerNewEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ApprovalTaskService approvalTaskService;

    @MockitoBean
    private ApprovalWorkflowService approvalWorkflowService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ApprovalTaskResponse sampleResponse(UUID id, String taskStatus) {
        return new ApprovalTaskResponse(id, UUID.randomUUID(), "ER-1001", "mgr-jane", 1,
                taskStatus, "ok", null, null, null, UUID.randomUUID(), 1, null, "SEQUENTIAL");
    }

    @Test
    void approve_returns200_forManager() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("mgr-jane");
        when(approvalWorkflowService.approve(eq(taskId), eq("mgr-jane"), any()))
                .thenReturn(sampleResponse(taskId, "APPROVED"));

        mockMvc.perform(post("/xms/manager/approvals/{taskId}/approve", taskId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ApprovalActionRequest("looks good"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("APPROVED"));
    }

    @Test
    void approve_returns403_forEmployee() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/xms/manager/approvals/{taskId}/approve", taskId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_EMPLOYEE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_returns200_forAdmin() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("mgr-jane");
        when(approvalWorkflowService.reject(eq(taskId), eq("mgr-jane"), any()))
                .thenReturn(sampleResponse(taskId, "REJECTED"));

        mockMvc.perform(post("/xms/manager/approvals/{taskId}/reject", taskId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ApprovalActionRequest("not compliant"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("REJECTED"));
    }

    @Test
    void myQueue_returns200_forEmployee() throws Exception {
        when(currentUserService.getEmployeeId()).thenReturn("mgr-jane");
        when(approvalWorkflowService.getMyQueue("mgr-jane"))
                .thenReturn(List.of(sampleResponse(UUID.randomUUID(), "PENDING")));

        mockMvc.perform(get("/xms/manager/approvals/my-queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_EMPLOYEE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].taskStatus").value("PENDING"));
    }

    @Test
    void myQueue_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/xms/manager/approvals/my-queue"))
                .andExpect(status().isUnauthorized());
    }
}
