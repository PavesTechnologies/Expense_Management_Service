package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.AssignedProjectResponse;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.integration.pms.PmsClient;
import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.EmployeeProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EmployeeProjectServiceImpl implements EmployeeProjectService {

    private final PmsClient pmsClient;
    private final ProjectCacheRepository projectCacheRepository;
    private final CurrentUserService currentUserService;

    @Override
    public List<AssignedProjectResponse> getAssignedProjects() {
        Long callerUmsUserId = currentUserService.getUmsUserId();
        log.info("Resolved caller UMS user id {} for assigned-projects lookup", callerUmsUserId);

        List<PmsProjectSummaryResponse> pmsProjects = pmsClient.getMyActiveProjects(callerUmsUserId);
        log.info("PMS returned {} project(s) for UMS user id {}", pmsProjects.size(), callerUmsUserId);

        List<AssignedProjectResponse> mapped = pmsProjects.stream().map(this::upsertAndMap).toList();
        log.info("Mapped/upserted {} assigned project(s) for UMS user id {}", mapped.size(), callerUmsUserId);
        return mapped;
    }

    /**
     * Upserts a PMS-returned project into {@code project_cache} keyed by {@code pmsProjectId}
     * (never {@code projectCode}, which PMS allows to change). {@code /api/my-work} carries only
     * the id and name, so the project's own record ({@code /api/projects/{id}}) supplies the real
     * {@code projectKey}, name and status. The client is still re-resolved from PMS when a line
     * item is actually saved (see {@code ExpenseLineItemServiceImpl}).
     */
    private AssignedProjectResponse upsertAndMap(PmsProjectSummaryResponse pmsProject) {
        Optional<PmsProjectDetailResponse> detail = fetchProjectDetail(pmsProject.id());
        ProjectCache cache = projectCacheRepository.findByPmsProjectId(pmsProject.id())
                .orElseGet(ProjectCache::new);
        cache.setPmsProjectId(pmsProject.id());

        String projectKey = detail.map(PmsProjectDetailResponse::projectKey).orElse(pmsProject.projectKey());
        if (projectKey != null) {
            cache.setProjectCode(projectKey);
        } else if (cache.getProjectCode() == null) {
            // Placeholder only until PMS's real key is known - never overwrites a real key.
            cache.setProjectCode("PMS-" + pmsProject.id());
        }
        String projectName = detail.map(PmsProjectDetailResponse::name).orElse(pmsProject.name());
        cache.setProjectName(projectName != null ? projectName : cache.getProjectCode());
        if (detail.isPresent()) {
            cache.setStatus(detail.get().status());
            cache.setClientId(detail.get().clientId());
        }
        cache.setSyncedAt(LocalDateTime.now());

        ProjectCache saved = projectCacheRepository.save(cache);
        return new AssignedProjectResponse(saved.getProjectId(), saved.getProjectCode(), saved.getPmsProjectId(),
                saved.getProjectName(), saved.getStatus(), saved.getClientId());
    }

    /** A failed detail lookup degrades to my-work's id/name rather than failing the whole picker. */
    private Optional<PmsProjectDetailResponse> fetchProjectDetail(Long pmsProjectId) {
        try {
            return pmsClient.getProject(pmsProjectId);
        } catch (RestClientException ex) {
            log.warn("[PMS-IN] GET /api/projects/{} failed; using my-work data only: {}", pmsProjectId, ex.getMessage());
            return Optional.empty();
        }
    }
}
