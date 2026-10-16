package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.dto.response.ClientResponse;
import com.expense_management_service.integration.rms.RmsClient;
import com.expense_management_service.integration.rms.dto.RmsActiveClientResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientServiceImplTest {

    @Mock
    private RmsClient rmsClient;

    private ClientServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClientServiceImpl(rmsClient);
    }

    @Test
    void getActiveClients_mapsAndSortsByNameCaseInsensitively() {
        UUID zeta = UUID.randomUUID();
        UUID acme = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        when(rmsClient.getActiveClients()).thenReturn(List.of(
                new RmsActiveClientResponse(zeta, "Zeta"),
                new RmsActiveClientResponse(acme, "acme"),
                new RmsActiveClientResponse(beta, "Beta")));

        List<ClientResponse> result = service.getActiveClients();

        assertThat(result).containsExactly(
                new ClientResponse(acme, "acme"),
                new ClientResponse(beta, "Beta"),
                new ClientResponse(zeta, "Zeta"));
    }

    @Test
    void getActiveClients_skipsEntriesWithoutId_andSortsNullNamesLast() {
        UUID unnamed = UUID.randomUUID();
        UUID acme = UUID.randomUUID();
        when(rmsClient.getActiveClients()).thenReturn(List.of(
                new RmsActiveClientResponse(unnamed, null),
                new RmsActiveClientResponse(null, "Orphan"),
                new RmsActiveClientResponse(acme, "Acme")));

        List<ClientResponse> result = service.getActiveClients();

        assertThat(result).containsExactly(
                new ClientResponse(acme, "Acme"),
                new ClientResponse(unnamed, null));
    }

    @Test
    void getActiveClients_returnsEmptyList_whenRmsHasNone() {
        when(rmsClient.getActiveClients()).thenReturn(List.of());

        assertThat(service.getActiveClients()).isEmpty();
    }

    @Test
    void getActiveClients_wrapsRmsHttpError() {
        when(rmsClient.getActiveClients())
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.getActiveClients())
                .isInstanceOf(IntegrationUnavailableException.class)
                .hasMessageContaining("RMS")
                .hasCauseInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void getActiveClients_wrapsRmsUnreachable() {
        when(rmsClient.getActiveClients()).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.getActiveClients())
                .isInstanceOf(IntegrationUnavailableException.class)
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void getActiveClients_throwsAccessDenied_whenRmsRejectsCallerRole() {
        when(rmsClient.getActiveClients())
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        assertThatThrownBy(() -> service.getActiveClients())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getActiveClients_throwsAccessDenied_whenRmsRejectsCallerToken() {
        when(rmsClient.getActiveClients())
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThatThrownBy(() -> service.getActiveClients())
                .isInstanceOf(AccessDeniedException.class);
    }
}
