package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import com.expense_management_service.service.SystemConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/system-configurations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SystemConfigurationController {

    private final SystemConfigurationService systemConfigurationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SystemConfigurationResponse> create(@Valid @RequestBody SystemConfigurationRequest request) {
        return ApiResponse.success("System configuration created", systemConfigurationService.create(request));
    }

    @PutMapping("/{configId}")
    public ApiResponse<SystemConfigurationResponse> update(@PathVariable UUID configId,
                                                            @Valid @RequestBody SystemConfigurationRequest request) {
        return ApiResponse.success("System configuration updated", systemConfigurationService.update(configId, request));
    }

    @GetMapping("/{configId}")
    public ApiResponse<SystemConfigurationResponse> getById(@PathVariable UUID configId) {
        return ApiResponse.success(systemConfigurationService.getById(configId));
    }

    @GetMapping
    public ApiResponse<List<SystemConfigurationResponse>> getAll() {
        return ApiResponse.success(systemConfigurationService.getAll());
    }

    @DeleteMapping("/{configId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID configId) {
        systemConfigurationService.delete(configId);
    }
}
