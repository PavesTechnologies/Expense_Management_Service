package com.expense_management_service.integration.pms;

import com.expense_management_service.integration.pms.dto.PmsMyWorkResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;

/**
 * {@link PmsClient} implementation backed by the shared {@code pmsRestClient} {@link RestClient}
 * bean (see {@code RestClientConfig}). Both endpoint paths below are verified directly against
 * PMS source — see {@link PmsMyWorkResponse}'s and {@code PmsProjectDetailResponse}'s javadocs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmsClientImpl implements PmsClient {

    private final RestClient pmsRestClient;

    @Override
    public List<PmsProjectSummaryResponse> getMyActiveProjects(Long umsUserId) {
        // Built via the URI-builder lambda (not a "{userId}" path template + separate arg) so
        // there is no template-expansion mechanism to get wrong — userId is a required query
        // parameter on this PMS endpoint, supplied directly.
        log.info("[PMS-OUT] GET /api/my-work?userId={}", umsUserId);
        PmsMyWorkResponse response;
        try {
            response = pmsRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/my-work").queryParam("userId", umsUserId).build())
                    .retrieve()
                    .body(PmsMyWorkResponse.class);
        } catch (RestClientResponseException ex) {
            // Never silently convert a PMS failure into an empty project list — log and rethrow
            // so it surfaces as the 502 GlobalExceptionHandler already maps RestClientResponseException to.
            log.warn("[PMS-IN] GET /api/my-work?userId={} failed with status {}", umsUserId, ex.getStatusCode());
            throw ex;
        }

        int workGroupCount = response != null && response.projects() != null ? response.projects().size() : 0;
        log.info("[PMS-IN] GET /api/my-work?userId={} returned {} project group(s)", umsUserId, workGroupCount);

        if (response == null || response.projects() == null) {
            return List.of();
        }
        return response.projects().stream()
                .map(group -> new PmsProjectSummaryResponse(group.projectId(), null, group.projectName(), null, null))
                .toList();
    }

    @Override
    public Optional<PmsProjectDetailResponse> getProject(Long pmsProjectId) {
        try {
            return Optional.ofNullable(pmsRestClient.get()
                    .uri("/api/projects/{id}", pmsProjectId)
                    .retrieve()
                    .body(PmsProjectDetailResponse.class));
        } catch (RestClientResponseException ex) {
            // PMS confirmed to return 400 (not 404) for "project not found" on this endpoint.
            if (isNotFound(ex.getStatusCode())) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private boolean isNotFound(HttpStatusCode status) {
        return status.value() == 400 || status.value() == 404;
    }
}
