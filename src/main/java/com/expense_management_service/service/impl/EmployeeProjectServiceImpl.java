package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.AssignedProjectResponse;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.integration.pms.PmsClient;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.EmployeeProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
     * (never {@code projectCode}, which PMS allows to change). The project's client is resolved
     * from PMS only when a line item is actually saved (see {@code ExpenseLineItemServiceImpl}).
     */
    private AssignedProjectResponse upsertAndMap(PmsProjectSummaryResponse pmsProject) {
        ProjectCache cache = projectCacheRepository.findByPmsProjectId(pmsProject.id())
                .orElseGet(ProjectCache::new);
        cache.setPmsProjectId(pmsProject.id());
        cache.setProjectCode(pmsProject.projectKey() != null ? pmsProject.projectKey() : "PMS-" + pmsProject.id());
        cache.setProjectName(pmsProject.name() != null ? pmsProject.name() : cache.getProjectCode());
        cache.setStatus(pmsProject.status());
        cache.setSyncedAt(LocalDateTime.now());

        ProjectCache saved = projectCacheRepository.save(cache);
        return new AssignedProjectResponse(saved.getProjectId(), saved.getProjectCode(), saved.getProjectName(), saved.getStatus());
    }
}
