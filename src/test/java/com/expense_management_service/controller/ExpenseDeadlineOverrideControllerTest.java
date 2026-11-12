package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.DeadlineOverrideRequest;
import com.expense_management_service.dto.response.DeadlineOverrideResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.impl.ExpenseDeadlineOverrideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseDeadlineOverrideController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExpenseDeadlineOverrideControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseDeadlineOverrideService deadlineOverrideService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID reportId;

    @BeforeEach
    void setUp() {
        reportId = UUID.randomUUID();
    }

    private static final String EXTEND_JSON =
            "{\"action\":\"EXTEND\",\"extendedUntil\":\"2026-12-31\",\"reason\":\"Receipts delayed by client visit\"}";

    @Test
    void extend_passesTheDateAndReasonToTheService() throws Exception {
        when(deadlineOverrideService.apply(any(UUID.class), any(DeadlineOverrideRequest.class))).thenReturn(
                new DeadlineOverrideResponse(reportId, "EXTEND", LocalDate.of(2026, 12, 31), false,
                        LocalDate.of(2026, 12, 31), "FIN001"));

        mockMvc.perform(post("/xms/finance/expense-reports/{reportId}/deadline-override", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EXTEND_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionDeadline").value("2026-12-31"));

        ArgumentCaptor<DeadlineOverrideRequest> captor = ArgumentCaptor.forClass(DeadlineOverrideRequest.class);
        verify(deadlineOverrideService).apply(any(UUID.class), captor.capture());
        assertEquals(LocalDate.of(2026, 12, 31), captor.getValue().extendedUntil());
        assertEquals("Receipts delayed by client visit", captor.getValue().reason());
    }

    @Test
    void reopen_hasNoDeadlineInTheResponse() throws Exception {
        when(deadlineOverrideService.apply(any(UUID.class), any(DeadlineOverrideRequest.class))).thenReturn(
                new DeadlineOverrideResponse(reportId, "REOPEN", null, true, null, "FIN001"));

        mockMvc.perform(post("/xms/finance/expense-reports/{reportId}/deadline-override", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REOPEN\",\"reason\":\"Receipts delayed by client visit\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reopened").value(true))
                .andExpect(jsonPath("$.data.submissionDeadline").doesNotExist());
    }

    @Test
    void rejectsAMissingReason_before_theServiceRuns() throws Exception {
        mockMvc.perform(post("/xms/finance/expense-reports/{reportId}/deadline-override", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REOPEN\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deadlineOverrideService);
    }

    @Test
    void returns403_forAGeneralUser() throws Exception {
        mockMvc.perform(post("/xms/finance/expense-reports/{reportId}/deadline-override", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EXTEND_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(deadlineOverrideService);
    }
}
