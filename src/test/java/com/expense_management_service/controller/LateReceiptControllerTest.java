package com.expense_management_service.controller;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.LateReceiptResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_AP_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LateReceiptController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class LateReceiptControllerTest {

    private static final String REASON = "The hotel sent the receipt two months after checkout";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReceiptService receiptService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static LateReceiptResponse pending(UUID receiptId) {
        return new LateReceiptResponse(receiptId, UUID.randomUUID(), "EXP-001", UUID.randomUUID(), "5100014",
                LocalDate.of(2026, 11, 5), new BigDecimal("2500.00"), "hotel-receipt.pdf", "5100014",
                LocalDateTime.of(2027, 2, 13, 10, 0), LocalDate.of(2027, 2, 13), REASON, "UPLOADED",
                "PENDING_REVIEW", null, null, null, "NONE", null,
                "DIRECT", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 7), null, null);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "hotel-receipt.pdf", "application/pdf", "%PDF-1.4 late".getBytes());
    }

    @Test
    void employee_canAttachALateReceipt_andTheServiceGetsTheDatesAndReason() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        when(receiptService.attachLate(eq(lineItemId), any(), eq(LocalDate.of(2027, 2, 13)), eq(REASON)))
                .thenReturn(pending(UUID.randomUUID()));

        mockMvc.perform(multipart("/xms/employee/expense-line-items/{lineItemId}/late-receipts", lineItemId)
                        .file(file())
                        .param("actualReceiptDate", "2027-02-13")
                        .param("reason", REASON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING_REVIEW"));
        verify(receiptService).attachLate(eq(lineItemId), any(), eq(LocalDate.of(2027, 2, 13)), eq(REASON));
    }

    @Test
    void manager_cannotAttachALateReceipt_andTheServiceIsNotReached() throws Exception {
        mockMvc.perform(multipart("/xms/employee/expense-line-items/{lineItemId}/late-receipts", UUID.randomUUID())
                        .file(file())
                        .param("actualReceiptDate", "2027-02-13")
                        .param("reason", REASON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(receiptService);
    }

    @Test
    void attachRefused_byTheBusinessRules_isReturnedAs422() throws Exception {
        when(receiptService.attachLate(any(), any(), any(), any())).thenThrow(new BusinessRuleViolationException(
                "A late receipt can be attached only after the report is approved, reimbursed or closed."));

        mockMvc.perform(multipart("/xms/employee/expense-line-items/{lineItemId}/late-receipts", UUID.randomUUID())
                        .file(file())
                        .param("actualReceiptDate", "2027-02-13")
                        .param("reason", REASON)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("approved, reimbursed or closed")));
    }

    @Test
    void apExecutive_seesThePendingQueue() throws Exception {
        when(receiptService.getLateReceiptsForReview(null)).thenReturn(List.of(pending(UUID.randomUUID())));

        mockMvc.perform(get("/xms/finance/late-receipts")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_AP_EXECUTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void finance_canAskForTheReviewedHistory_byStatus() throws Exception {
        when(receiptService.getLateReceiptsForReview("ALL")).thenReturn(List.of(pending(UUID.randomUUID()), pending(UUID.randomUUID())));

        mockMvc.perform(get("/xms/finance/late-receipts").param("status", "ALL")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_AP_EXECUTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void employee_cannotSeeThePendingQueue() throws Exception {
        mockMvc.perform(get("/xms/finance/late-receipts")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(receiptService);
    }

    @Test
    void finance_verifies_withANote() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(receiptService.verifyLate(eq(receiptId), eq("Matches the folio"))).thenReturn(pending(receiptId));

        mockMvc.perform(post("/xms/finance/late-receipts/{receiptId}/verify", receiptId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Matches the folio\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk());
        verify(receiptService).verifyLate(receiptId, "Matches the folio");
    }

    @Test
    void finance_rejects_withAReason_andABlankBodyReachesTheServiceAsNull() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(receiptService.rejectLate(eq(receiptId), any())).thenReturn(pending(receiptId));

        mockMvc.perform(post("/xms/finance/late-receipts/{receiptId}/reject", receiptId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Total does not match\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk());
        verify(receiptService).rejectLate(receiptId, "Total does not match");

        mockMvc.perform(post("/xms/finance/late-receipts/{receiptId}/reject", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk());
        verify(receiptService).rejectLate(receiptId, null);
    }

    @Test
    void managerCannotReviewLateReceipts() throws Exception {
        mockMvc.perform(post("/xms/finance/late-receipts/{receiptId}/verify", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(receiptService);
    }
}
