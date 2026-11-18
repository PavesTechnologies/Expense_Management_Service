package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ReceiptReleaseDecisionRequest;
import com.expense_management_service.dto.request.ReceiptReleaseRequest;
import com.expense_management_service.dto.response.ReceiptReleaseResponse;
import com.expense_management_service.dto.response.SupplementaryReceiptResponse;
import com.expense_management_service.service.ReceiptReleaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Two-person receipt release. One Finance user requests (RECEIPT_RELEASE_REQUEST); a different Finance user approves or
 * rejects (RECEIPT_RELEASE_APPROVE). Both the role and the permission are required, and the service refuses self-approval.
 */
@RestController
@RequestMapping("/xms/finance/receipt-releases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE')")
public class FinanceReceiptReleaseController {

    private final ReceiptReleaseService service;

    @GetMapping("/candidates")
    @PreAuthorize("hasAuthority('RECEIPT_RELEASE_REQUEST')")
    public ApiResponse<List<SupplementaryReceiptResponse>> candidates(@RequestParam String employeeId) {
        return ApiResponse.success(service.candidatesFor(employeeId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RECEIPT_RELEASE_REQUEST')")
    public ApiResponse<ReceiptReleaseResponse> request(@Valid @RequestBody ReceiptReleaseRequest request) {
        return ApiResponse.success("Release requested. A different Finance user must approve it.", service.request(request));
    }

    @GetMapping("/queue")
    public ApiResponse<List<ReceiptReleaseResponse>> queue() {
        return ApiResponse.success(service.queue());
    }

    @GetMapping("/receipt/{receiptId}")
    public ApiResponse<List<ReceiptReleaseResponse>> history(@PathVariable UUID receiptId) {
        return ApiResponse.success(service.historyFor(receiptId));
    }

    @PostMapping("/{releaseId}/approve")
    @PreAuthorize("hasAuthority('RECEIPT_RELEASE_APPROVE')")
    public ApiResponse<ReceiptReleaseResponse> approve(@PathVariable UUID releaseId,
                                                       @RequestBody(required = false) ReceiptReleaseDecisionRequest decision) {
        return ApiResponse.success("Release approved.", service.approve(releaseId, decision));
    }

    @PostMapping("/{releaseId}/reject")
    @PreAuthorize("hasAuthority('RECEIPT_RELEASE_APPROVE')")
    public ApiResponse<ReceiptReleaseResponse> reject(@PathVariable UUID releaseId,
                                                      @RequestBody(required = false) ReceiptReleaseDecisionRequest decision) {
        return ApiResponse.success("Release rejected.", service.reject(releaseId, decision));
    }
}
