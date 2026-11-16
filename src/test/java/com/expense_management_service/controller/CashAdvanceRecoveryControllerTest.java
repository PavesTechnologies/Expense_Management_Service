package com.expense_management_service.controller;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.impl.CashAdvanceRecoveryService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CashAdvanceRecoveryController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceRecoveryControllerTest {

    private static final String BASE = "/xms/finance/cash-advance-recovery";
    private static final String REASON = "Finance confirmed the salary deduction";
    private static final String REASON_JSON = "{\"reason\":\"" + REASON + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceRecoveryService recoveryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private String sendToPayrollUrl(UUID caseId) {
        return BASE + "/cases/" + caseId + "/send-to-payroll";
    }

    @Test
    void generalUser_cannotSendToPayroll_andTheServiceIsNotReached() throws Exception {
        mockMvc.perform(post(sendToPayrollUrl(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(REASON_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recoveryService);
    }

    @Test
    void manager_cannotSendToPayroll_becausePayrollIsFinanceOnly() throws Exception {
        mockMvc.perform(post(sendToPayrollUrl(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(REASON_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recoveryService);
    }

    @Test
    void finance_sendToPayroll_reachesTheServiceWithTheCaseAndReason_andReturnsTheSentCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(recoveryService.sendToPayroll(eq(caseId), eq(REASON))).thenReturn(sentCase(caseId));

        mockMvc.perform(post(sendToPayrollUrl(caseId)).contentType(MediaType.APPLICATION_JSON).content(REASON_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(CashAdvanceRecoveryCase.SENT_TO_PAYROLL));
        verify(recoveryService).sendToPayroll(caseId, REASON);
    }

    @Test
    void financeExecutive_mayAlsoSendToPayroll() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(recoveryService.sendToPayroll(eq(caseId), any())).thenReturn(sentCase(caseId));

        mockMvc.perform(post(sendToPayrollUrl(caseId)).contentType(MediaType.APPLICATION_JSON).content(REASON_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE_EXECUTIVE))))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateOrInvalidStatus_isRefusedByTheService_andReturnedAs422() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(recoveryService.sendToPayroll(eq(caseId), any())).thenThrow(new BusinessRuleViolationException(
                "Only a case awaiting Finance review can be sent to payroll (current status: SENT_TO_PAYROLL)."));

        mockMvc.perform(post(sendToPayrollUrl(caseId)).contentType(MediaType.APPLICATION_JSON).content(REASON_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("awaiting Finance review")));
    }

    private static CashAdvanceRecoveryCase sentCase(UUID caseId) {
        return CashAdvanceRecoveryCase.builder()
                .recoveryCaseId(caseId)
                .advance(CashAdvance.builder().advanceId(UUID.randomUUID()).build())
                .employeeId("EMP001")
                .amountAtNotice(new BigDecimal("800.00"))
                .status(CashAdvanceRecoveryCase.SENT_TO_PAYROLL)
                .build();
    }
}
