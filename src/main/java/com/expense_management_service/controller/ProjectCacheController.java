package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ProjectCacheRequest;
import com.expense_management_service.dto.response.ProjectCacheResponse;
import com.expense_management_service.service.ProjectCacheService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/projects")
@RequiredArgsConstructor
public class ProjectCacheController {

    private final ProjectCacheService projectCacheService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectCacheResponse> create(@Valid @RequestBody ProjectCacheRequest request) {
        return ApiResponse.success("Project created", projectCacheService.create(request));
    }

    @PutMapping("/{projectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectCacheResponse> update(@PathVariable UUID projectId, @Valid @RequestBody ProjectCacheRequest request) {
        return ApiResponse.success("Project updated", projectCacheService.update(projectId, request));
    }

    @GetMapping("/{projectId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<ProjectCacheResponse> getById(@PathVariable UUID projectId) {
        return ApiResponse.success(projectCacheService.getById(projectId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<ProjectCacheResponse>> getAll() {
        return ApiResponse.success(projectCacheService.getAll());
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID projectId) {
        projectCacheService.delete(projectId);
    }
}
