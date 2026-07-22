package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ProjectCacheRequest;
import com.expense_management_service.dto.response.ProjectCacheResponse;
import com.expense_management_service.entity.ProjectCache;
import org.springframework.stereotype.Component;

@Component
public class ProjectCacheMapper {

    public ProjectCache toEntity(ProjectCacheRequest request) {
        return ProjectCache.builder()
                .projectCode(request.projectCode())
                .projectName(request.projectName())
                .clientName(request.clientName())
                .status(request.status())
                .build();
    }

    public void updateEntity(ProjectCache entity, ProjectCacheRequest request) {
        entity.setProjectCode(request.projectCode());
        entity.setProjectName(request.projectName());
        entity.setClientName(request.clientName());
        entity.setStatus(request.status());
    }

    public ProjectCacheResponse toResponse(ProjectCache entity) {
        return new ProjectCacheResponse(
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getProjectName(),
                entity.getClientName(),
                entity.getStatus(),
                entity.getSyncedAt()
        );
    }
}
