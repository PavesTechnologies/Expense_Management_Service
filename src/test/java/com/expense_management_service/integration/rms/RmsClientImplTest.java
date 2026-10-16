package com.expense_management_service.integration.rms;

import com.expense_management_service.integration.rms.dto.RmsActiveClientResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the real HTTP call against a mocked RMS. Response bodies match RMS's
 * {@code ClientController#getActiveClientsPMS} ({@code ApiResponse<List<ActiveClientsPMSDTO>>}).
 * Token forwarding lives in {@code RestClientConfig}'s interceptor, not in this builder.
 */
class RmsClientImplTest {

    private static final String ACTIVE_CLIENTS_URL = "http://rms.test/rms/api/client/get-active-clients";

    private MockRestServiceServer mockServer;
    private RmsClientImpl rmsClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rms.test/rms");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        rmsClient = new RmsClientImpl(builder.build());
    }

    @Test
    void getActiveClients_unwrapsDataEnvelope() {
        UUID acme = UUID.randomUUID();
        UUID globex = UUID.randomUUID();
        mockServer.expect(requestTo(ACTIVE_CLIENTS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "message": "Fetched Active Clients Successfully",
                          "data": [
                            {"clientId": "%s", "clientName": "Acme"},
                            {"clientId": "%s", "clientName": "Globex"}
                          ]
                        }
                        """.formatted(acme, globex), MediaType.APPLICATION_JSON));

        List<RmsActiveClientResponse> result = rmsClient.getActiveClients();

        assertThat(result).containsExactly(
                new RmsActiveClientResponse(acme, "Acme"),
                new RmsActiveClientResponse(globex, "Globex"));
        mockServer.verify();
    }

    @Test
    void getActiveClients_returnsEmptyList_whenDataIsNull() {
        mockServer.expect(requestTo(ACTIVE_CLIENTS_URL))
                .andRespond(withSuccess("""
                        {"success": true, "message": "Fetched Active Clients Successfully", "data": null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(rmsClient.getActiveClients()).isEmpty();
    }

    @Test
    void getActiveClients_propagatesUnauthorizedWithoutRetry() {
        mockServer.expect(once(), requestTo(ACTIVE_CLIENTS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> rmsClient.getActiveClients())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        mockServer.verify();
    }

    @Test
    void getActiveClients_propagatesForbidden_forCallerWithoutRmsRole() {
        mockServer.expect(once(), requestTo(ACTIVE_CLIENTS_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> rmsClient.getActiveClients())
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        mockServer.verify();
    }

    @Test
    void getActiveClients_propagatesRmsServerError() {
        // RMS's getActiveClientsPMS has no try/catch, so a DB failure surfaces as a 500.
        mockServer.expect(requestTo(ACTIVE_CLIENTS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> rmsClient.getActiveClients())
                .isInstanceOf(HttpServerErrorException.class);
    }
}
