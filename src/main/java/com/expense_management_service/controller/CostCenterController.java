package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.service.CostCenterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/cost-centers")
@RequiredArgsConstructor
public class CostCenterController {

    private final CostCenterService costCenterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CostCenterResponse> create(@Valid @RequestBody CostCenterRequest request) {
        return ApiResponse.success("Cost center created", costCenterService.create(request));
    }

    @PutMapping("/{costCenterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CostCenterResponse> update(@PathVariable UUID costCenterId, @Valid @RequestBody CostCenterRequest request) {
        return ApiResponse.success("Cost center updated", costCenterService.update(costCenterId, request));
    }

    @GetMapping("/{costCenterId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CostCenterResponse> getById(@PathVariable UUID costCenterId) {
        return ApiResponse.success(costCenterService.getById(costCenterId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<List<CostCenterResponse>> getAll() {
        return ApiResponse.success(costCenterService.getAll());
    }

    @DeleteMapping("/{costCenterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID costCenterId) {
        costCenterService.delete(costCenterId);
    }
}
