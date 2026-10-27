package com.expense_management_service.controller;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract for {@code POST /xms/finance/reimbursements}: JSON only, with the fields of
 * {@link CashAdvanceAdjustmentRequest}. Form bodies (multipart or urlencoded) are rejected with 415.
 */
@WebMvcTest(CashAdvanceAdjustmentController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceAdjustmentControllerTest {

    private static final String ENDPOINT = "/xms/finance/reimbursements";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceAdjustmentService cashAdvanceAdjustmentService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID advanceId;
    private UUID reportId;
    private UUID adjustmentId;

    @BeforeEach
    void setUp() {
        advanceId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        adjustmentId = UUID.randomUUID();
    }

    private String validJson() {
        return """
                {"advanceId":"%s","reportId":"%s","adjustedAmount":250.00,"adjustedBy":"FIN001"}
                """.formatted(advanceId, reportId);
    }

    @Test
    void create_acceptsJson_andPassesEveryFieldToService() throws Exception {
        when(cashAdvanceAdjustmentService.create(any(CashAdvanceAdjustmentRequest.class))).thenReturn(
                new CashAdvanceAdjustmentResponse(adjustmentId, advanceId, reportId,
                        new BigDecimal("250.00"), "FIN001", LocalDateTime.now()));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.adjustmentId").value(adjustmentId.toString()));

        ArgumentCaptor<CashAdvanceAdjustmentRequest> captor = ArgumentCaptor.forClass(CashAdvanceAdjustmentRequest.class);
        verify(cashAdvanceAdjustmentService).create(captor.capture());
        CashAdvanceAdjustmentRequest sent = captor.getValue();
        assertEquals(advanceId, sent.advanceId());
        assertEquals(reportId, sent.reportId());
        assertEquals(0, sent.adjustedAmount().compareTo(new BigDecimal("250.00")));
        assertEquals("FIN001", sent.adjustedBy());
    }

    @Test
    void create_rejectsMultipartForm_with415_andNeverReachesTheService() throws Exception {
        mockMvc.perform(multipart(ENDPOINT)
                        .param("cashAdvanceId", advanceId.toString())
                        .param("expenseReportId", reportId.toString())
                        .param("adjustmentAmount", "250.00")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(cashAdvanceAdjustmentService);
    }

    @Test
    void create_rejectsUrlEncodedForm_with415_andNeverReachesTheService() throws Exception {
        // axios sends FormData as form-urlencoded when no multipart Content-Type is set; this must not reach the service.
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("advanceId=" + advanceId + "&reportId=" + reportId + "&adjustedAmount=250.00")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(cashAdvanceAdjustmentService);
    }

    @Test
    void create_returns400_whenReportIdIsMissing() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"advanceId\":\"%s\",\"adjustedAmount\":250.00}".formatted(advanceId))
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reportId")));
        verifyNoInteractions(cashAdvanceAdjustmentService);
    }

    @Test
    void create_returns400_whenAmountIsZeroOrNegative() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"advanceId\":\"%s\",\"reportId\":\"%s\",\"adjustedAmount\":0}".formatted(advanceId, reportId))
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("adjustedAmount")));
        verifyNoInteractions(cashAdvanceAdjustmentService);
    }

    @Test
    void create_returns403_forGeneralUser() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cashAdvanceAdjustmentService);
    }

    @Test
    void create_returns422_withTheServiceMessage_whenAReportIsNotApproved() throws Exception {
        when(cashAdvanceAdjustmentService.create(any(CashAdvanceAdjustmentRequest.class)))
                .thenThrow(new BusinessRuleViolationException(
                        "Expense report must be fully approved and finance-verified before adjusting against a cash advance. Current report status: DRAFT"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fully approved")));
    }
}
