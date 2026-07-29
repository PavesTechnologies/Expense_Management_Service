package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateRefreshResponse;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ExchangeRateService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
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
 * Web-layer slice test for {@link ExchangeRateController}: verifies the REST contract and
 * RBAC enforcement (401/403/200/201) without a real database or UMS connection —
 * {@link ExchangeRateService} is mocked and {@link JwtDecoder} is stubbed purely to satisfy
 * {@link SecurityConfig}'s bean graph, since {@code jwt()} test support injects the
 * authentication directly and never invokes it.
 */
@WebMvcTest(ExchangeRateController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ExchangeRateResponse sampleResponse(UUID id) {
        return new ExchangeRateResponse(id, UUID.randomUUID(), "USD", UUID.randomUUID(), "INR",
                BigDecimal.valueOf(83.25), LocalDate.now(), "MANUAL",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void create_returns201_forAdmin() throws Exception {
        ExchangeRateRequest request = new ExchangeRateRequest(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(83.25), LocalDate.now(), "MANUAL");
        UUID id = UUID.randomUUID();
        when(exchangeRateService.create(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/xms/admin/exchange-rates")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fromCurrencyCode").value("USD"));
    }

    @Test
    void create_returns403_forFinance() throws Exception {
        ExchangeRateRequest request = new ExchangeRateRequest(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(83.25), LocalDate.now(), "MANUAL");

        mockMvc.perform(post("/xms/admin/exchange-rates")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_returns401_whenUnauthenticated() throws Exception {
        ExchangeRateRequest request = new ExchangeRateRequest(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(83.25), LocalDate.now(), "MANUAL");

        mockMvc.perform(post("/xms/admin/exchange-rates")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_returns200_forFinance() throws Exception {
        UUID id = UUID.randomUUID();
        when(exchangeRateService.getById(eq(id))).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/xms/admin/exchange-rates/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exchangeRateId").value(id.toString()));
    }

    @Test
    void getById_returns403_forEmployee() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/xms/admin/exchange-rates/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAll_returnsUnfilteredList_whenNoQueryParamsProvided() throws Exception {
        when(exchangeRateService.getAll()).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/exchange-rates")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fromCurrencyCode").value("USD"));
    }

    @Test
    void getAll_delegatesToGetFiltered_whenDateFromAndToProvided() throws Exception {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 3, 1);
        when(exchangeRateService.getFiltered(date, fromId, toId)).thenReturn(List.of(sampleResponse(UUID.randomUUID())));

        mockMvc.perform(get("/xms/admin/exchange-rates")
                        .param("date", date.toString())
                        .param("from", fromId.toString())
                        .param("to", toId.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fromCurrencyCode").value("USD"));
    }

    @Test
    void refresh_returns200_forAdmin() throws Exception {
        ExchangeRateRefreshResponse refreshResponse = new ExchangeRateRefreshResponse(
                2, 1, 1, LocalDateTime.now(), "Refresh completed: 1 new rate(s) recorded out of 2 pair(s) scanned.");
        when(exchangeRateService.refreshRates()).thenReturn(refreshResponse);

        mockMvc.perform(post("/xms/admin/exchange-rates/refresh")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pairsProcessed").value(2))
                .andExpect(jsonPath("$.data.ratesCreated").value(1));
    }

    @Test
    void refresh_returns403_forFinance() throws Exception {
        mockMvc.perform(post("/xms/admin/exchange-rates/refresh")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void refresh_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/xms/admin/exchange-rates/refresh"))
                .andExpect(status().isUnauthorized());
    }
}
