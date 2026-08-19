package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.ApPaymentQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ApPaymentService;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_AP_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Layer 1 role gate: hasRole('AP_EXECUTIVE') only - matches FinanceVerificationController's role-only convention. */
@WebMvcTest(ApPaymentController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ApPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApPaymentService apPaymentService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor apExecutive() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_AP_EXECUTIVE));
    }

    private static RequestPostProcessor general() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL));
    }

    private static RequestPostProcessor reportingManager() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER));
    }

    private static RequestPostProcessor financeExecutive() {
        return jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE_EXECUTIVE));
    }

    private ExpenseReportResponse sampleReportResponse(String status) {
        return new ExpenseReportResponse(UUID.randomUUID(), "EXP-0001", "5100001", "Trip", "Client visit", "2026",
                UUID.randomUUID(), "Engineering", status, "PAYMENT_COMPLETED", UUID.randomUUID(), "INR",
                new BigDecimal("1000"), new BigDecimal("1000"),
                LocalDateTime.now(), LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now(), 1, false, false, 0, 0);
    }

    @Test
    void getApQueue_returns200_forApExecutive() throws Exception {
        ApPaymentQueueItemResponse item = new ApPaymentQueueItemResponse(
                UUID.randomUUID(), "EXP-0001", "5100001", "Trip", new BigDecimal("50000"), "INR",
                UUID.randomUUID(), "Engineering", LocalDateTime.now(), "APPROVED", "APPROVED_FOR_PAYMENT");
        when(apPaymentService.getApQueue(any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/xms/ap-payments/queue").with(apExecutive()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportNumber").value("EXP-0001"));
    }

    @Test
    void getApQueue_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/xms/ap-payments/queue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getApQueue_returns403_forGeneralRole() throws Exception {
        mockMvc.perform(get("/xms/ap-payments/queue").with(general()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getApQueue_returns403_forReportingManagerRole() throws Exception {
        mockMvc.perform(get("/xms/ap-payments/queue").with(reportingManager()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getApQueue_returns403_forFinanceExecutiveRole() throws Exception {
        mockMvc.perform(get("/xms/ap-payments/queue").with(financeExecutive()))
                .andExpect(status().isForbidden());
    }

    @Test
    void markPaymentCompleted_returns200_forApExecutive() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(currentUserService.getEmployeeId()).thenReturn("5100200");
        when(apPaymentService.markPaymentCompleted(eq(reportId), eq("5100200")))
                .thenReturn(sampleReportResponse("APPROVED"));

        mockMvc.perform(post("/xms/ap-payments/{reportId}/complete", reportId).with(apExecutive()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentRoutingStatus").value("PAYMENT_COMPLETED"));
    }

    @Test
    void markPaymentCompleted_returns403_forGeneralRole() throws Exception {
        mockMvc.perform(post("/xms/ap-payments/{reportId}/complete", UUID.randomUUID()).with(general()))
                .andExpect(status().isForbidden());
    }

    @Test
    void markPaymentCompleted_returns403_forReportingManagerRole() throws Exception {
        mockMvc.perform(post("/xms/ap-payments/{reportId}/complete", UUID.randomUUID()).with(reportingManager()))
                .andExpect(status().isForbidden());
    }

    @Test
    void markPaymentCompleted_returns403_forFinanceExecutiveRole() throws Exception {
        mockMvc.perform(post("/xms/ap-payments/{reportId}/complete", UUID.randomUUID()).with(financeExecutive()))
                .andExpect(status().isForbidden());
    }
}
