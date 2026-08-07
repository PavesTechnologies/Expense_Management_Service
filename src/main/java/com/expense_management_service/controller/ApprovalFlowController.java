package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.CatchAllFlowRequest;
import com.expense_management_service.dto.response.ApprovalFlowResponse;
import com.expense_management_service.service.ApprovalFlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin-only configuration surface for the Approval Flow Engine (§12.3). */
@RestController
@RequestMapping("/xms/admin/approval-flows")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ApprovalFlowController {

    private final ApprovalFlowService approvalFlowService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalFlowResponse> create(@Valid @RequestBody ApprovalFlowRequest request) {
        return ApiResponse.success("Approval flow created", approvalFlowService.create(request));
    }

    @PutMapping("/{flowId}")
    public ApiResponse<ApprovalFlowResponse> update(@PathVariable UUID flowId, @Valid @RequestBody ApprovalFlowRequest request) {
        return ApiResponse.success("Approval flow updated", approvalFlowService.update(flowId, request));
    }

    @GetMapping("/{flowId}")
    public ApiResponse<ApprovalFlowResponse> getById(@PathVariable UUID flowId) {
        return ApiResponse.success(approvalFlowService.getById(flowId));
    }

    @GetMapping
    public ApiResponse<List<ApprovalFlowResponse>> getAll() {
        return ApiResponse.success(approvalFlowService.getAll());
    }

    @DeleteMapping("/{flowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID flowId) {
        approvalFlowService.delete(flowId);
    }

    @GetMapping("/catch-all")
    public ApiResponse<ApprovalFlowResponse> getCatchAllFlow() {
        return ApiResponse.success(approvalFlowService.getCatchAllFlow());
    }

    @PutMapping("/catch-all")
    public ApiResponse<ApprovalFlowResponse> updateCatchAllFlow(@Valid @RequestBody CatchAllFlowRequest request) {
        return ApiResponse.success("Catch-all approval flow updated", approvalFlowService.updateCatchAllFlow(request));
    }
}
