package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ProjectCacheRequest;
import com.expense_management_service.dto.response.ProjectCacheResponse;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.mapper.ProjectCacheMapper;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.service.ProjectCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectCacheServiceImpl implements ProjectCacheService {

    private final ProjectCacheRepository projectCacheRepository;
    private final ProjectCacheMapper projectCacheMapper;

    @Override
    public ProjectCacheResponse create(ProjectCacheRequest request) {
        ProjectCache entity = projectCacheMapper.toEntity(request);
        entity.setSyncedAt(LocalDateTime.now());
        return projectCacheMapper.toResponse(projectCacheRepository.save(entity));
    }

    @Override
    public ProjectCacheResponse update(UUID projectId, ProjectCacheRequest request) {
        ProjectCache entity = findEntity(projectId);
        projectCacheMapper.updateEntity(entity, request);
        entity.setSyncedAt(LocalDateTime.now());
        return projectCacheMapper.toResponse(projectCacheRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectCacheResponse getById(UUID projectId) {
        return projectCacheMapper.toResponse(findEntity(projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProjectCacheResponse> getAll(Pageable pageable) {
        return projectCacheRepository.findAll(pageable).map(projectCacheMapper::toResponse);
    }

    @Override
    public void delete(UUID projectId) {
        projectCacheRepository.delete(findEntity(projectId));
    }

    private ProjectCache findEntity(UUID projectId) {
        return projectCacheRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectCache not found with id: " + projectId));
    }
}
