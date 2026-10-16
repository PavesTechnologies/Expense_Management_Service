package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.ExpenseSplitResponse;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ExpenseSplitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_AP_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseSplitReviewController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExpenseSplitReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseSplitService expenseSplitService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void financeAndApReviewers_seeTheSplitAllocation() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        when(expenseSplitService.getSplitsForReview(lineItemId)).thenReturn(List.of(
                new ExpenseSplitResponse(UUID.randomUUID(), lineItemId, UUID.randomUUID(), "Engineering", SplitType.PERCENTAGE,
                        new BigDecimal("50"), new BigDecimal("250.0000"), 0, LocalDateTime.now()),
                new ExpenseSplitResponse(UUID.randomUUID(), lineItemId, UUID.randomUUID(), "HR", SplitType.PERCENTAGE,
                        new BigDecimal("50"), new BigDecimal("250.0000"), 1, LocalDateTime.now())));

        for (String role : List.of(ROLE_FINANCE_EXECUTIVE, ROLE_AP_EXECUTIVE)) {
            mockMvc.perform(get("/xms/finance/expense-line-items/{id}/splits", lineItemId)
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[1].costCenterName").value("HR"))
                    .andExpect(jsonPath("$.data[1].allocatedAmount").value(250.0));
        }
    }

    @Test
    void ordinaryEmployee_cannotUseTheReviewEndpoint() throws Exception {
        mockMvc.perform(get("/xms/finance/expense-line-items/{id}/splits", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(expenseSplitService);
    }
}
