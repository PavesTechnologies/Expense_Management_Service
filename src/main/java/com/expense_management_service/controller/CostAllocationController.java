package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CostAllocationRequest;
import com.expense_management_service.dto.response.CostAllocationResponse;
import com.expense_management_service.service.CostAllocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cost-allocations")
@RequiredArgsConstructor
public class CostAllocationController {

    private final CostAllocationService costAllocationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CostAllocationResponse> create(@Valid @RequestBody CostAllocationRequest request) {
        return ApiResponse.success("Cost allocation created", costAllocationService.create(request));
    }

    @PutMapping("/{allocationId}")
    public ApiResponse<CostAllocationResponse> update(@PathVariable UUID allocationId,
                                                       @Valid @RequestBody CostAllocationRequest request) {
        return ApiResponse.success("Cost allocation updated", costAllocationService.update(allocationId, request));
    }

    @GetMapping("/{allocationId}")
    public ApiResponse<CostAllocationResponse> getById(@PathVariable UUID allocationId) {
        return ApiResponse.success(costAllocationService.getById(allocationId));
    }

    @GetMapping
    public ApiResponse<Page<CostAllocationResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(costAllocationService.getAll(pageable));
    }

    @DeleteMapping("/{allocationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID allocationId) {
        costAllocationService.delete(allocationId);
    }
}
