package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ProjectCacheRequest;
import com.expense_management_service.dto.response.ProjectCacheResponse;
import com.expense_management_service.service.ProjectCacheService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectCacheController {

    private final ProjectCacheService projectCacheService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectCacheResponse> create(@Valid @RequestBody ProjectCacheRequest request) {
        return ApiResponse.success("Project created", projectCacheService.create(request));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectCacheResponse> update(@PathVariable UUID projectId, @Valid @RequestBody ProjectCacheRequest request) {
        return ApiResponse.success("Project updated", projectCacheService.update(projectId, request));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectCacheResponse> getById(@PathVariable UUID projectId) {
        return ApiResponse.success(projectCacheService.getById(projectId));
    }

    @GetMapping
    public ApiResponse<Page<ProjectCacheResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(projectCacheService.getAll(pageable));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId) {
        projectCacheService.delete(projectId);
    }
}
