package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CostAllocationRequest;
import com.expense_management_service.dto.response.CostAllocationResponse;
import com.expense_management_service.service.CostAllocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/finance/cost-allocations")
@RequiredArgsConstructor
public class CostAllocationController {

    private final CostAllocationService costAllocationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CostAllocationResponse> create(@Valid @RequestBody CostAllocationRequest request) {
        return ApiResponse.success("Cost allocation created", costAllocationService.create(request));
    }

    @PutMapping("/{allocationId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CostAllocationResponse> update(@PathVariable UUID allocationId,
                                                       @Valid @RequestBody CostAllocationRequest request) {
        return ApiResponse.success("Cost allocation updated", costAllocationService.update(allocationId, request));
    }

    @GetMapping("/{allocationId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','EMPLOYEE')")
    public ApiResponse<CostAllocationResponse> getById(@PathVariable UUID allocationId) {
        return ApiResponse.success(costAllocationService.getById(allocationId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','EMPLOYEE')")
    public ApiResponse<List<CostAllocationResponse>> getAll() {
        return ApiResponse.success(costAllocationService.getAll());
    }

    @DeleteMapping("/{allocationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID allocationId) {
        costAllocationService.delete(allocationId);
    }
}
