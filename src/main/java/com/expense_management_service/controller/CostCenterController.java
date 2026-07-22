package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.service.CostCenterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cost-centers")
@RequiredArgsConstructor
public class CostCenterController {

    private final CostCenterService costCenterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CostCenterResponse> create(@Valid @RequestBody CostCenterRequest request) {
        return ApiResponse.success("Cost center created", costCenterService.create(request));
    }

    @PutMapping("/{costCenterId}")
    public ApiResponse<CostCenterResponse> update(@PathVariable UUID costCenterId, @Valid @RequestBody CostCenterRequest request) {
        return ApiResponse.success("Cost center updated", costCenterService.update(costCenterId, request));
    }

    @GetMapping("/{costCenterId}")
    public ApiResponse<CostCenterResponse> getById(@PathVariable UUID costCenterId) {
        return ApiResponse.success(costCenterService.getById(costCenterId));
    }

    @GetMapping
    public ApiResponse<Page<CostCenterResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(costCenterService.getAll(pageable));
    }

    @DeleteMapping("/{costCenterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID costCenterId) {
        costCenterService.delete(costCenterId);
    }
}
