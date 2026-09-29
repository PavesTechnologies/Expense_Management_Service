package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.InvoiceHandoffRequest;
import com.expense_management_service.dto.response.InvoiceHandoffEligibleExpenseResponse;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.InvoiceHandoffService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE_EXECUTIVE;
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
 * Web-layer slice test for {@link InvoiceHandoffQueueController} (Epic 8 invoice-team handoff).
 * Eligibility and duplicate-handoff logic are covered by {@code InvoiceHandoffServiceImplTest};
 * this only verifies the REST contract and that access is restricted to FINANCE_EXECUTIVE.
 */
@WebMvcTest(InvoiceHandoffQueueController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class InvoiceHandoffQueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private InvoiceHandoffService invoiceHandoffService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static InvoiceHandoffEligibleExpenseResponse sampleEligible(UUID lineItemId) {
        return new InvoiceHandoffEligibleExpenseResponse(lineItemId, UUID.randomUUID(), "ER-001", "emp-1",
                LocalDate.now(), "Travel", "desc", null, "USD", null, "INR", null, null,
                UUID.randomUUID(), "ALPHA", "Project Alpha", UUID.randomUUID(), "Aurora Health", 0);
    }

    @Test
    void getEligibleExpenses_returns200_forFinanceExecutive() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        when(invoiceHandoffService.getEligibleExpenses(any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new PageResponse<>(List.of(sampleEligible(lineItemId)), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/xms/finance/invoice-handoff-queue/eligible-expenses")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE_EXECUTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].lineItemId").value(lineItemId.toString()));
    }

    @Test
    void getEligibleExpenses_returns403_forGeneralEmployee() throws Exception {
        mockMvc.perform(get("/xms/finance/invoice-handoff-queue/eligible-expenses")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getEligibleExpenses_returns403_forGenericFinanceRole() throws Exception {
        // Deliberately reuses FINANCE_EXECUTIVE, not the broader FINANCE role, for this queue.
        mockMvc.perform(get("/xms/finance/invoice-handoff-queue/eligible-expenses")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void markHandedOff_returns200_forFinanceExecutive() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        when(invoiceHandoffService.markHandedOff(eq(lineItemId), any())).thenReturn(
                new InvoiceSyncResponse(UUID.randomUUID(), lineItemId, "INV-1", "HANDED_OFF", LocalDateTime.now(), 0, null));

        mockMvc.perform(post("/xms/finance/invoice-handoff-queue/{lineItemId}/handoff", lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE_EXECUTIVE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new InvoiceHandoffRequest("INV-1", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("HANDED_OFF"));
    }

    @Test
    void markHandedOff_returns401_whenUnauthenticated() throws Exception {
        UUID lineItemId = UUID.randomUUID();

        mockMvc.perform(post("/xms/finance/invoice-handoff-queue/{lineItemId}/handoff", lineItemId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new InvoiceHandoffRequest("INV-1", null, null))))
                .andExpect(status().isUnauthorized());
    }
}
