package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import com.expense_management_service.service.SystemConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/system-configurations")
@RequiredArgsConstructor
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
    public ApiResponse<Page<SystemConfigurationResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(systemConfigurationService.getAll(pageable));
    }

    @DeleteMapping("/{configId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID configId) {
        systemConfigurationService.delete(configId);
    }
}
