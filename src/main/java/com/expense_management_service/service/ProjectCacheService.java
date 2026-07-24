package com.expense_management_service.service;

import com.expense_management_service.dto.request.ProjectCacheRequest;
import com.expense_management_service.dto.response.ProjectCacheResponse;
import java.util.List;
import java.util.UUID;

public interface ProjectCacheService {

    ProjectCacheResponse create(ProjectCacheRequest request);

    ProjectCacheResponse update(UUID projectId, ProjectCacheRequest request);

    ProjectCacheResponse getById(UUID projectId);

    List<ProjectCacheResponse> getAll();

    void delete(UUID projectId);
}
