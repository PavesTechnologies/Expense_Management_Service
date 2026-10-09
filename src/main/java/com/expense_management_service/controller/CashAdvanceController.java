package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.CashAdvanceSettlementResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.CashAdvanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Cash Advances", description = "Cash Advance employee lifecycle & manager approval endpoints")
@RestController
@RequestMapping("/xms/employee/cash-advances")
@RequiredArgsConstructor
public class CashAdvanceController {

    private final CashAdvanceService cashAdvanceService;
    private final CurrentUserService currentUserService;

    @Operation(summary = "Create a cash advance request in DRAFT status")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceResponse> create(@Valid @RequestBody CashAdvanceRequest request) {
        return ApiResponse.success("Cash advance created", cashAdvanceService.create(request));
    }

    @Operation(summary = "Update an existing draft cash advance request")
    @PutMapping("/{advanceId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceResponse> update(@PathVariable UUID advanceId, @Valid @RequestBody CashAdvanceRequest request) {
        return ApiResponse.success("Cash advance updated", cashAdvanceService.update(advanceId, request));
    }

    @Operation(summary = "Get cash advances created by the authenticated employee")
    @GetMapping("/my-advances")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','FINANCE_EXECUTIVE','MANAGER')")
    public ApiResponse<List<CashAdvanceResponse>> getMyAdvances(@RequestParam(required = false) String status) {
        return ApiResponse.success(cashAdvanceService.getMyAdvances(currentUserService.getEmployeeId(), status));
    }

    @Operation(summary = "Get cash advance requests awaiting the authenticated manager's approval")
    @GetMapping("/my-approvals")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','FINANCE_EXECUTIVE','GENERAL')")
    public ApiResponse<List<CashAdvanceResponse>> getMyApprovals(@RequestParam(required = false) String status) {
        return ApiResponse.success(cashAdvanceService.getMyApprovals(currentUserService.getEmployeeId(), status));
    }

    @Operation(summary = "Get cash advance details by advance ID")
    @GetMapping("/{advanceId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','FINANCE_EXECUTIVE','MANAGER')")
    public ApiResponse<CashAdvanceResponse> getById(@PathVariable UUID advanceId) {
        return ApiResponse.success(cashAdvanceService.getById(advanceId));
    }

    @Operation(summary = "Get cash advances with optional employeeId and status filters")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','FINANCE_EXECUTIVE','MANAGER')")
    public ApiResponse<List<CashAdvanceResponse>> getAll(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(cashAdvanceService.getFiltered(employeeId, status));
    }

    @Operation(summary = "Submit a draft cash advance request for manager approval")
    @PostMapping("/{advanceId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceResponse> submit(@PathVariable UUID advanceId) {
        return ApiResponse.success("Cash advance submitted for approval", cashAdvanceService.submit(advanceId));
    }

    @Operation(summary = "Approve a submitted cash advance request (Authorized Manager/Delegate/Admin)")
    @PostMapping("/{advanceId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','FINANCE_EXECUTIVE','GENERAL') or hasAuthority('EXPENSE_APPROVE')")
    public ApiResponse<CashAdvanceResponse> approve(@PathVariable UUID advanceId) {
        return ApiResponse.success("Cash advance approved", cashAdvanceService.approve(advanceId, currentUserService.getEmployeeId()));
    }

    @Operation(summary = "Reject a submitted cash advance request with mandatory reason (Authorized Manager/Delegate/Admin)")
    @PostMapping("/{advanceId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','FINANCE_EXECUTIVE','GENERAL') or hasAuthority('EXPENSE_APPROVE')")
    public ApiResponse<CashAdvanceResponse> reject(@PathVariable UUID advanceId, @RequestParam(required = false) String reason) {
        return ApiResponse.success("Cash advance rejected", cashAdvanceService.reject(advanceId, currentUserService.getEmployeeId(), reason));
    }

    @Operation(summary = "Disburse an approved cash advance (Finance/Admin)")
    @PostMapping("/{advanceId}/disburse")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceResponse> disburse(@PathVariable UUID advanceId) {
        return ApiResponse.success("Cash advance disbursed", cashAdvanceService.disburse(advanceId, currentUserService.getEmployeeId()));
    }

    @Operation(summary = "Record employee cash advance repayment")
    @PostMapping("/{advanceId}/repayments")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceRepaymentResponse> repay(
            @PathVariable UUID advanceId,
            @Valid @RequestBody CashAdvanceRepaymentRequest request) {
        if (!advanceId.equals(request.advanceId())) {
            request = new CashAdvanceRepaymentRequest(advanceId, request.amount(), request.paymentMethod(), request.paymentReference(), request.notes());
        }
        return ApiResponse.success("Cash advance repayment recorded", cashAdvanceService.repay(request));
    }

    @Operation(summary = "Get cash advance settlement details")
    @GetMapping("/{advanceId}/settlement")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','FINANCE_EXECUTIVE','MANAGER')")
    public ApiResponse<CashAdvanceSettlementResponse> getSettlement(@PathVariable UUID advanceId) {
        return ApiResponse.success(cashAdvanceService.getSettlement(advanceId));
    }


    @Operation(summary = "Cancel a cash advance request")
    @PostMapping("/{advanceId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceResponse> cancel(@PathVariable UUID advanceId) {
        return ApiResponse.success("Cash advance cancelled", cashAdvanceService.cancel(advanceId));
    }

    @Operation(summary = "Delete a cash advance request (Admin only)")
    @DeleteMapping("/{advanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID advanceId) {
        cashAdvanceService.delete(advanceId);
    }
}

