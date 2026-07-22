package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import com.expense_management_service.service.ApprovalMatrixService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-matrices")
@RequiredArgsConstructor
public class ApprovalMatrixController {

    private final ApprovalMatrixService approvalMatrixService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalMatrixResponse> create(@Valid @RequestBody ApprovalMatrixRequest request) {
        return ApiResponse.success("Approval matrix created", approvalMatrixService.create(request));
    }

    @PutMapping("/{matrixId}")
    public ApiResponse<ApprovalMatrixResponse> update(@PathVariable UUID matrixId,
                                                       @Valid @RequestBody ApprovalMatrixRequest request) {
        return ApiResponse.success("Approval matrix updated", approvalMatrixService.update(matrixId, request));
    }

    @GetMapping("/{matrixId}")
    public ApiResponse<ApprovalMatrixResponse> getById(@PathVariable UUID matrixId) {
        return ApiResponse.success(approvalMatrixService.getById(matrixId));
    }

    @GetMapping
    public ApiResponse<Page<ApprovalMatrixResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(approvalMatrixService.getAll(pageable));
    }

    @DeleteMapping("/{matrixId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID matrixId) {
        approvalMatrixService.delete(matrixId);
    }
}
