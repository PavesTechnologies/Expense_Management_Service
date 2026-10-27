package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CarryForwardDecisionRequest;
import com.expense_management_service.dto.request.CashAdvanceCarryForwardRequest;
import com.expense_management_service.dto.response.CashAdvanceCarryForwardResponse;
import com.expense_management_service.service.impl.CashAdvanceCarryForwardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Carry-forward of a cash advance's unspent balance to another advance. Proposal is open to the employee and Finance;
 * approval, rejection and reversal are Finance only, and the server refuses approval by the requester.
 * The feature is off until {@code expense.carry-forward.enabled=true}.
 */
@RestController
@RequestMapping("/xms/cash-advance-carry-forwards")
@RequiredArgsConstructor
public class CashAdvanceCarryForwardController {

    private final CashAdvanceCarryForwardService carryForwardService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE','GENERAL')")
    public ApiResponse<CashAdvanceCarryForwardResponse> propose(@Valid @RequestBody CashAdvanceCarryForwardRequest request) {
        return ApiResponse.success("Carry-forward proposed", carryForwardService.propose(request));
    }

    @GetMapping("/{carryForwardId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceCarryForwardResponse> get(@PathVariable UUID carryForwardId) {
        return ApiResponse.success(carryForwardService.get(carryForwardId));
    }

    @PostMapping(value = "/{carryForwardId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceCarryForwardResponse> approve(@PathVariable UUID carryForwardId,
                                                                @Valid @RequestBody CarryForwardDecisionRequest request) {
        return ApiResponse.success("Carry-forward approved and applied", carryForwardService.approve(carryForwardId, request));
    }

    @PostMapping(value = "/{carryForwardId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceCarryForwardResponse> reject(@PathVariable UUID carryForwardId,
                                                               @Valid @RequestBody CarryForwardDecisionRequest request) {
        return ApiResponse.success("Carry-forward rejected", carryForwardService.reject(carryForwardId, request));
    }

    @PostMapping(value = "/{carryForwardId}/reverse", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceCarryForwardResponse> reverse(@PathVariable UUID carryForwardId,
                                                                @Valid @RequestBody CarryForwardDecisionRequest request) {
        return ApiResponse.success("Carry-forward reversed", carryForwardService.reverse(carryForwardId, request));
    }
}
