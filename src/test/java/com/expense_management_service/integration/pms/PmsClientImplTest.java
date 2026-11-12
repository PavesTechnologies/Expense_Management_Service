package com.expense_management_service.integration.pms;

import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the actual HTTP call construction against a mocked server — the class covered by
 * neither an interface-level mock (used everywhere else this client is consumed) nor Maven's
 * compiler, and the one that shipped the real "Not enough variable values available to expand
 * 'userId'" bug when {@code getMyActiveProjects} used a "{userId}" path-template placeholder
 * against PMS's actual query-param-based /api/my-work endpoint.
 */
class PmsClientImplTest {

    private MockRestServiceServer mockServer;
    private PmsClientImpl pmsClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pms.test/pms");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        pmsClient = new PmsClientImpl(builder.build());
    }

    @Test
    void getMyActiveProjects_mapsProjectsFromMyWorkResponse() {
        mockServer.expect(requestTo("http://pms.test/pms/api/my-work?userId=9001"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "overdueCount": 2,
                          "activeProjectCount": 2,
                          "projects": [
                            {"projectId": 501, "projectName": "Alpha", "urgencyFlag": "NONE", "items": []},
                            {"projectId": 777, "projectName": "Beta", "urgencyFlag": "OVERDUE", "items": []}
                          ],
                          "testWork": [],
                          "PROJECT_MANAGERItems": []
                        }
                        """, MediaType.APPLICATION_JSON));

        List<PmsProjectSummaryResponse> result = pmsClient.getMyActiveProjects(9001L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(501L);
        assertThat(result.get(0).name()).isEqualTo("Alpha");
        // Verified from PMS source: /api/my-work never returns a project key or status.
        assertThat(result.get(0).projectKey()).isNull();
        assertThat(result.get(0).status()).isNull();
        mockServer.verify();
    }

    @Test
    void getMyActiveProjects_returnsEmptyList_whenNoProjects() {
        mockServer.expect(requestTo("http://pms.test/pms/api/my-work?userId=9002"))
                .andRespond(withSuccess("""
                        {"overdueCount": 0, "activeProjectCount": 0, "projects": [], "testWork": [], "PROJECT_MANAGERItems": []}
                        """, MediaType.APPLICATION_JSON));

        assertThat(pmsClient.getMyActiveProjects(9002L)).isEmpty();
    }

    @Test
    void getMyActiveProjects_propagatesAuthFailure() {
        mockServer.expect(requestTo("http://pms.test/pms/api/my-work?userId=9003"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> pmsClient.getMyActiveProjects(9003L))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getMyActiveProjects_propagatesPmsServerError() {
        mockServer.expect(requestTo("http://pms.test/pms/api/my-work?userId=9004"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> pmsClient.getMyActiveProjects(9004L))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
    }

    @Test
    void getMyActiveProjects_usesQueryParamNotPathTemplate() {
        // Regression guard for the actual shipped bug: userId must appear as a query parameter
        // on /api/my-work, never as a "{userId}" path-template placeholder.
        mockServer.expect(requestTo("http://pms.test/pms/api/my-work?userId=42"))
                .andRespond(withSuccess("""
                        {"projects": []}
                        """, MediaType.APPLICATION_JSON));

        pmsClient.getMyActiveProjects(42L);

        mockServer.verify();
    }

    @Test
    void getProject_returnsDetail_whenFound() {
        UUID clientId = UUID.randomUUID();
        mockServer.expect(requestTo("http://pms.test/pms/api/projects/501"))
                .andRespond(withSuccess("""
                        {"id": 501, "projectKey": "ALPHA", "name": "Alpha", "status": "ACTIVE", "clientId": "%s", "ownerId": 42}
                        """.formatted(clientId), MediaType.APPLICATION_JSON));

        Optional<PmsProjectDetailResponse> result = pmsClient.getProject(501L);

        assertThat(result).isPresent();
        assertThat(result.get().clientId()).isEqualTo(clientId);
    }

    @Test
    void getProject_returnsEmpty_whenPmsReturns400NotFound() {
        // PMS confirmed to return 400 (not 404) for "project not found" on this endpoint.
        mockServer.expect(requestTo("http://pms.test/pms/api/projects/999"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(pmsClient.getProject(999L)).isEmpty();
    }
}
