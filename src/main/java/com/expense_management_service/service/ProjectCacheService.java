package com.expense_management_service.service;

import com.expense_management_service.dto.request.ProjectCacheRequest;
import com.expense_management_service.dto.response.ProjectCacheResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ProjectCacheService {

    ProjectCacheResponse create(ProjectCacheRequest request);

    ProjectCacheResponse update(UUID projectId, ProjectCacheRequest request);

    ProjectCacheResponse getById(UUID projectId);

    Page<ProjectCacheResponse> getAll(Pageable pageable);

    void delete(UUID projectId);
}
