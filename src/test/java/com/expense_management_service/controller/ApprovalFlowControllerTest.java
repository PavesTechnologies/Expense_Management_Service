package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.ApprovalFlowCriterionRequest;
import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.ApprovalLevelApproverRequest;
import com.expense_management_service.dto.request.ApprovalLevelRequest;
import com.expense_management_service.dto.response.ApprovalFlowResponse;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ApprovalFlowService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApprovalFlowController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ApprovalFlowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ApprovalFlowService approvalFlowService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private ApprovalFlowRequest sampleRequest() {
        return new ApprovalFlowRequest("Travel over 10k", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "10000")),
                List.of(new ApprovalLevelRequest(1, LevelQuorum.SEQUENTIAL,
                        List.of(new ApprovalLevelApproverRequest(1, ApproverSourceType.REPORTING_MANAGER, null)))),
                "ACTIVE");
    }

    private ApprovalFlowResponse sampleResponse(UUID flowId) {
        return new ApprovalFlowResponse(flowId, "Travel over 10k", 1, "1", false, "ACTIVE", List.of(), List.of(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void create_returns201_forAdmin() throws Exception {
        when(approvalFlowService.create(any())).thenReturn(sampleResponse(UUID.randomUUID()));

        mockMvc.perform(post("/xms/admin/approval-flows")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Travel over 10k"));
    }

    @Test
    void create_returns403_forGeneralEmployee() throws Exception {
        mockMvc.perform(post("/xms/admin/approval-flows")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/admin/approval-flows")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAll_returns200_forAdmin() throws Exception {
        when(approvalFlowService.getAll()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/approval-flows")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Travel over 10k"));
    }

    @Test
    void getCatchAllFlow_returns200_forAdmin() throws Exception {
        UUID catchAllId = UUID.randomUUID();
        when(approvalFlowService.getCatchAllFlow()).thenReturn(
                new ApprovalFlowResponse(catchAllId, "Catch-All", null, null, true, "ACTIVE", List.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(get("/xms/admin/approval-flows/catch-all")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isCatchAll").value(true));
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID flowId = UUID.randomUUID();

        mockMvc.perform(delete("/xms/admin/approval-flows/{flowId}", flowId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }
}
