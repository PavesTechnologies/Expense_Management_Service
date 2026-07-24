package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.IntegrationSyncRequest;
import com.expense_management_service.dto.response.IntegrationSyncResponse;
import com.expense_management_service.service.IntegrationSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/integration-syncs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class IntegrationSyncController {

    private final IntegrationSyncService integrationSyncService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IntegrationSyncResponse> create(@Valid @RequestBody IntegrationSyncRequest request) {
        return ApiResponse.success("Integration sync created", integrationSyncService.create(request));
    }

    @PutMapping("/{integrationId}")
    public ApiResponse<IntegrationSyncResponse> update(@PathVariable UUID integrationId,
                                                        @Valid @RequestBody IntegrationSyncRequest request) {
        return ApiResponse.success("Integration sync updated", integrationSyncService.update(integrationId, request));
    }

    @GetMapping("/{integrationId}")
    public ApiResponse<IntegrationSyncResponse> getById(@PathVariable UUID integrationId) {
        return ApiResponse.success(integrationSyncService.getById(integrationId));
    }

    @GetMapping
    public ApiResponse<List<IntegrationSyncResponse>> getAll() {
        return ApiResponse.success(integrationSyncService.getAll());
    }

    @DeleteMapping("/{integrationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID integrationId) {
        integrationSyncService.delete(integrationId);
    }
}
