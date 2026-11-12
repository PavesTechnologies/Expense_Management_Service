package com.expense_management_service.controller;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.ClientResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_AP_EXECUTIVE;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for {@link ClientController}: REST contract, RBAC, and the 502 mapping of
 * RMS failures. RMS call/mapping logic is covered by {@code RmsClientImplTest} and
 * {@code ClientServiceImplTest}.
 */
@WebMvcTest(ClientController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ClientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClientService clientService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getActiveClients_returns200_forGeneralEmployee() throws Exception {
        UUID clientId = UUID.randomUUID();
        when(clientService.getActiveClients()).thenReturn(List.of(new ClientResponse(clientId, "Acme")));

        mockMvc.perform(get("/xms/clients")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.data[0].clientName").value("Acme"));
    }

    @Test
    void getActiveClients_returns200_forAdminFinanceAndManager() throws Exception {
        when(clientService.getActiveClients()).thenReturn(List.of());

        for (String role : List.of(ROLE_ADMIN, ROLE_FINANCE, ROLE_MANAGER)) {
            mockMvc.perform(get("/xms/clients")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    void getActiveClients_returns403_forRoleOutsideAllowList() throws Exception {
        mockMvc.perform(get("/xms/clients")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_AP_EXECUTIVE))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clientService);
    }

    @Test
    void getActiveClients_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/xms/clients"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(clientService);
    }

    @Test
    void getActiveClients_returns403_whenRmsRejectsCallerRole() throws Exception {
        when(clientService.getActiveClients())
                .thenThrow(new AccessDeniedException("RMS rejected the caller's token for client lookup (HTTP 403)"));

        mockMvc.perform(get("/xms/clients")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getActiveClients_returns502_whenRmsUnavailable() throws Exception {
        when(clientService.getActiveClients()).thenThrow(new IntegrationUnavailableException(
                "Couldn't load clients from RMS. Try again shortly.", new ResourceAccessException("refused")));

        mockMvc.perform(get("/xms/clients")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Couldn't load clients from RMS. Try again shortly."));
    }
}
