package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import com.expense_management_service.service.ApprovalMatrixService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/workflows")
@RequiredArgsConstructor
public class ApprovalMatrixController {

    private final ApprovalMatrixService approvalMatrixService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ApprovalMatrixResponse> create(@Valid @RequestBody ApprovalMatrixRequest request) {
        return ApiResponse.success("Approval matrix created", approvalMatrixService.create(request));
    }

    @PutMapping("/{matrixId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ApprovalMatrixResponse> update(@PathVariable UUID matrixId,
                                                       @Valid @RequestBody ApprovalMatrixRequest request) {
        return ApiResponse.success("Approval matrix updated", approvalMatrixService.update(matrixId, request));
    }

    @GetMapping("/{matrixId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<ApprovalMatrixResponse> getById(@PathVariable UUID matrixId) {
        return ApiResponse.success(approvalMatrixService.getById(matrixId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<ApprovalMatrixResponse>> getAll() {
        return ApiResponse.success(approvalMatrixService.getAll());
    }

    @DeleteMapping("/{matrixId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID matrixId) {
        approvalMatrixService.delete(matrixId);
    }
}
