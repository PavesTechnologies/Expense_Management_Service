package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.dto.response.ClientResponse;
import com.expense_management_service.integration.rms.RmsClient;
import com.expense_management_service.integration.rms.dto.RmsActiveClientResponse;
import com.expense_management_service.service.ClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientServiceImpl implements ClientService {

    private final RmsClient rmsClient;

    /**
     * RMS authorizes the caller's own forwarded token, so its 401/403 means this caller's role
     * isn't allowed to list clients in RMS — surfaced as 403, not as an outage (502).
     */
    @Override
    public List<ClientResponse> getActiveClients() {
        List<RmsActiveClientResponse> clients;
        try {
            clients = rmsClient.getActiveClients();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AccessDeniedException("RMS rejected the caller's token for client lookup (HTTP " + status + ")");
            }
            throw new IntegrationUnavailableException("Couldn't load clients from RMS. Try again shortly.", e);
        } catch (RestClientException e) {
            throw new IntegrationUnavailableException("Couldn't load clients from RMS. Try again shortly.", e);
        }
        log.info("RMS returned {} active client(s)", clients.size());
        return clients.stream()
                .filter(c -> c.clientId() != null)
                .map(c -> new ClientResponse(c.clientId(), c.clientName()))
                .sorted(Comparator.comparing(ClientResponse::clientName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }
}
