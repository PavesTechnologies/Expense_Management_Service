package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ReceiptReleaseService;
import com.expense_management_service.service.SupplementaryPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.expense_management_service.security.RoleConstants.ROLE_AP_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who can reach AP payment confirmation and Finance receipt release. Each check is fail closed. */
@WebMvcTest({ApSupplementaryPaymentController.class, FinanceReceiptReleaseController.class})
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class SupplementaryPaymentReleaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplementaryPaymentService paymentService;

    @MockitoBean
    private ReceiptReleaseService releaseService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void generalUserCannotReadTheApQueue() throws Exception {
        mockMvc.perform(get("/xms/ap/supplementary-payments/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void apExecutiveCanReadTheApQueue() throws Exception {
        mockMvc.perform(get("/xms/ap/supplementary-payments/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_AP_EXECUTIVE))))
                .andExpect(status().isOk());
    }

    @Test
    void financeUserCannotConfirmAPayment() throws Exception {
        mockMvc.perform(post("/xms/ap/supplementary-payments/11111111-1111-1111-1111-111111111111/confirm")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"bankReference\":\"BANK-REF-0001\",\"paymentMethod\":\"UPI\","
                                + "\"paymentDate\":\"2026-11-01\",\"amountPaid\":100}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void releaseQueueNeedsFinanceRoleNotOnlyAPermission() throws Exception {
        mockMvc.perform(get("/xms/finance/receipt-releases/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("RECEIPT_RELEASE_APPROVE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvingAReleaseNeedsTheApprovePermission() throws Exception {
        mockMvc.perform(post("/xms/finance/receipt-releases/11111111-1111-1111-1111-111111111111/approve")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestingAReleaseNeedsTheRequestPermission() throws Exception {
        mockMvc.perform(post("/xms/finance/receipt-releases")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"receiptId\":\"11111111-1111-1111-1111-111111111111\",\"reason\":\"Reason text here\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE),
                                new SimpleGrantedAuthority("RECEIPT_RELEASE_REQUEST"))))
                .andExpect(status().isCreated());
    }
}
