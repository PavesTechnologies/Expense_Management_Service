package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SupplementaryClaimCreateRequest;
import com.expense_management_service.dto.request.SupplementaryClaimItemRequest;
import com.expense_management_service.dto.response.SupplementaryClaimResponse;
import com.expense_management_service.dto.response.SupplementaryEligibilityResponse;
import com.expense_management_service.dto.response.SupplementaryReceiptResponse;
import com.expense_management_service.service.SupplementaryClaimService;
import com.expense_management_service.service.SupplementaryReceiptService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Employee self-service for supplementary expense claims. Each action is checked again in the service. */
@RestController
@RequestMapping("/xms/employee/supplementary-claims")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('GENERAL','MANAGER','REPORTING_MANAGER','FINANCE','FINANCE_EXECUTIVE','ADMIN')")
public class EmployeeSupplementaryClaimController {

    private final SupplementaryClaimService service;
    private final SupplementaryReceiptService receiptService;

    @PostMapping("/{claimId}/items/{itemId}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplementaryClaimResponse> uploadReceipt(@PathVariable UUID claimId, @PathVariable UUID itemId,
                                                                 @RequestParam("file") MultipartFile file) {
        receiptService.upload(claimId, itemId, file);
        return ApiResponse.success("Receipt uploaded and attached.", service.get(claimId));
    }

    @PostMapping("/{claimId}/items/{itemId}/receipts/{receiptId}")
    public ApiResponse<SupplementaryClaimResponse> attachReceipt(@PathVariable UUID claimId, @PathVariable UUID itemId,
                                                                 @PathVariable UUID receiptId) {
        receiptService.attach(claimId, itemId, receiptId);
        return ApiResponse.success("Receipt attached.", service.get(claimId));
    }

    @DeleteMapping("/{claimId}/items/{itemId}/receipts/{receiptId}")
    public ApiResponse<SupplementaryClaimResponse> detachReceipt(@PathVariable UUID claimId, @PathVariable UUID itemId,
                                                                 @PathVariable UUID receiptId) {
        receiptService.detach(claimId, itemId, receiptId);
        return ApiResponse.success("Receipt removed from the expense.", service.get(claimId));
    }

    /** Stores a receipt before its expense exists, so OCR can read it (the automatic path). */
    @PostMapping("/{claimId}/receipt-scans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<java.util.Map<String, UUID>> uploadScan(@PathVariable UUID claimId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Receipt uploaded. Reading it now.",
                java.util.Map.of("receiptId", receiptService.uploadScan(claimId, file)));
    }

    /** Creates an expense from a receipt uploaded for this claim, with the details the employee confirmed. */
    @PostMapping("/{claimId}/items/from-receipt/{receiptId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplementaryClaimResponse> addItemFromReceipt(@PathVariable UUID claimId, @PathVariable UUID receiptId,
                                                                      @Valid @RequestBody SupplementaryClaimItemRequest request) {
        return ApiResponse.success("Expense added from the receipt.", service.addItemFromReceipt(claimId, receiptId, request));
    }

    /** What OCR read from one of the caller's receipts, used to fill an expense from its receipt. */
    @GetMapping("/receipts/{receiptId}/suggestion")
    public ApiResponse<com.expense_management_service.dto.response.SupplementaryReceiptSuggestionResponse> suggestion(
            @PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.suggestionFor(receiptId));
    }

    /** Original report receipts that Finance released for a supplementary claim and that are not used yet. */
    @GetMapping("/releasable-receipts")
    public ApiResponse<List<SupplementaryReceiptResponse>> releasableReceipts() {
        return ApiResponse.success(receiptService.releasableForCurrentEmployee());
    }

    /** Every supplementary claim raised against one of the caller's own reports. */
    @GetMapping("/by-report/{reportId}")
    public ApiResponse<List<SupplementaryClaimResponse>> forReport(@PathVariable UUID reportId) {
        return ApiResponse.success(service.listForReport(reportId));
    }

    @GetMapping("/eligibility/{reportId}")
    public ApiResponse<SupplementaryEligibilityResponse> eligibility(@PathVariable UUID reportId) {
        return ApiResponse.success(service.checkEligibility(reportId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplementaryClaimResponse> create(@Valid @RequestBody SupplementaryClaimCreateRequest request) {
        return ApiResponse.success("Supplementary claim created as a draft.", service.createForSelf(request));
    }

    @GetMapping
    public ApiResponse<List<SupplementaryClaimResponse>> mine() {
        return ApiResponse.success(service.listMine());
    }

    @GetMapping("/{claimId}")
    public ApiResponse<SupplementaryClaimResponse> get(@PathVariable UUID claimId) {
        return ApiResponse.success(service.get(claimId));
    }

    @PostMapping("/{claimId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplementaryClaimResponse> addItem(@PathVariable UUID claimId,
                                                           @Valid @RequestBody SupplementaryClaimItemRequest request) {
        return ApiResponse.success(service.addItem(claimId, request));
    }

    @PutMapping("/{claimId}/items/{itemId}")
    public ApiResponse<SupplementaryClaimResponse> updateItem(@PathVariable UUID claimId, @PathVariable UUID itemId,
                                                              @Valid @RequestBody SupplementaryClaimItemRequest request) {
        return ApiResponse.success(service.updateItem(claimId, itemId, request));
    }

    @DeleteMapping("/{claimId}/items/{itemId}")
    public ApiResponse<SupplementaryClaimResponse> removeItem(@PathVariable UUID claimId, @PathVariable UUID itemId) {
        return ApiResponse.success(service.removeItem(claimId, itemId));
    }

    @PostMapping("/{claimId}/acknowledge")
    public ApiResponse<SupplementaryClaimResponse> acknowledge(@PathVariable UUID claimId) {
        return ApiResponse.success("Acknowledged. This does not approve the claim.", service.acknowledge(claimId));
    }

    @PostMapping("/{claimId}/submit")
    public ApiResponse<SupplementaryClaimResponse> submit(@PathVariable UUID claimId) {
        return ApiResponse.success("Supplementary claim submitted to your manager.", service.submit(claimId));
    }

    @PostMapping("/{claimId}/resubmit")
    public ApiResponse<SupplementaryClaimResponse> resubmit(@PathVariable UUID claimId) {
        return ApiResponse.success("Supplementary claim resubmitted.", service.resubmit(claimId));
    }

    @PostMapping("/{claimId}/cancel")
    public ApiResponse<SupplementaryClaimResponse> cancel(@PathVariable UUID claimId) {
        return ApiResponse.success("Supplementary claim cancelled.", service.cancel(claimId));
    }
}
