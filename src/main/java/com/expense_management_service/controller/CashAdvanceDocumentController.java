package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.CashAdvanceDocumentResponse;
import com.expense_management_service.enums.CashAdvanceDocumentCategory;
import com.expense_management_service.service.CashAdvanceDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Supporting documents for a cash advance request. Ownership and draft status are checked in the service. */
@RestController
@RequestMapping("/xms/employee/cash-advances/{advanceId}/documents")
@RequiredArgsConstructor
public class CashAdvanceDocumentController {

    private final CashAdvanceDocumentService documentService;

    @Operation(summary = "Attach a supporting document to a draft cash advance request")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ResponseEntity<ApiResponse<CashAdvanceDocumentResponse>> upload(
            @PathVariable UUID advanceId,
            @RequestParam("category") CashAdvanceDocumentCategory category,
            @RequestParam("file") MultipartFile file) {
        CashAdvanceDocumentResponse created = documentService.upload(advanceId, category, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document attached", created));
    }

    @Operation(summary = "List the supporting documents attached to a cash advance request")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<List<CashAdvanceDocumentResponse>> list(@PathVariable UUID advanceId) {
        return ApiResponse.success(documentService.list(advanceId));
    }

    @Operation(summary = "A short-lived link to view a supporting document")
    @GetMapping("/{documentId}/view")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<com.expense_management_service.dto.response.ReceiptUrlResponse> view(
            @PathVariable UUID advanceId, @PathVariable UUID documentId) {
        return ApiResponse.success(documentService.viewUrl(advanceId, documentId));
    }

    @Operation(summary = "A short-lived link that downloads a supporting document")
    @GetMapping("/{documentId}/download")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','MANAGER','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<com.expense_management_service.dto.response.ReceiptUrlResponse> download(
            @PathVariable UUID advanceId, @PathVariable UUID documentId) {
        return ApiResponse.success(documentService.downloadUrl(advanceId, documentId));
    }

    @Operation(summary = "Remove a supporting document from a draft cash advance request")
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public void delete(@PathVariable UUID advanceId, @PathVariable UUID documentId) {
        documentService.delete(advanceId, documentId);
    }
}
