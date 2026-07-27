package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import com.expense_management_service.service.ReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Receipt upload/retrieval/delete endpoints (Amazon S3-backed, V1).
 * <p>
 * Two resource roots share this controller: receipts are created/listed underneath their
 * parent line item ({@code /expense-line-items/{lineItemId}/receipts}), but read/deleted
 * directly by their own id ({@code /receipts/{receiptId}}) — mirroring how a receipt is
 * conceptually owned by a line item but is its own addressable resource once it exists.
 * Ownership and status-gating are enforced inside {@link ReceiptService}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Receipts", description = "Upload, view, download and delete expense receipt files (stored in Amazon S3)")
public class ReceiptController {

    private final ReceiptService receiptService;

    @PostMapping(value = "/xms/employee/expense-line-items/{lineItemId}/receipts", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    @Operation(
            summary = "Upload a receipt file for a line item",
            description = "Accepts a single multipart file. Allowed types: PDF, PNG, JPG, JPEG (validated by both "
                    + "declared content-type and actual file signature). Maximum size: 10MB. The line item's parent "
                    + "report must be Draft, Policy Rejected, or Query Raised, and must belong to the caller "
                    + "(Admins may act on any report)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Receipt uploaded",
                    content = @Content(schema = @Schema(implementation = ReceiptResponse.class), examples = @ExampleObject(
                            value = "{\"success\":true,\"message\":\"Receipt uploaded\",\"data\":{\"receiptId\":"
                                    + "\"6f1a1e2e-1111-4a2b-9c3d-abc123456789\",\"lineItemId\":\"3c2b1a0e-2222-4a2b-9c3d-abc123456789\","
                                    + "\"originalFileName\":\"taxi-receipt.pdf\",\"contentType\":\"application/pdf\",\"fileSize\":184320,"
                                    + "\"uploadedBy\":\"5100014\",\"uploadedAt\":\"2026-07-27T10:15:30\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Empty file, oversized file, disallowed type, or content that doesn't match its declared type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller does not own the parent report"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Line item not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "File exceeds the maximum allowed size"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Parent report is not in an editable status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Storage (S3) temporarily unavailable")
    })
    public ApiResponse<ReceiptResponse> upload(@PathVariable UUID lineItemId,
                                                @Parameter(description = "The receipt file (PDF/PNG/JPG/JPEG, max 10MB)")
                                                @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Receipt uploaded", receiptService.upload(lineItemId, file));
    }

    @GetMapping("/xms/employee/expense-line-items/{lineItemId}/receipts")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    @Operation(
            summary = "List receipts on a line item",
            description = "Returns metadata only — no pre-signed URLs are generated here. Call the /view or "
                    + "/download endpoint for a specific receipt when a URL is actually needed."
    )
    public ApiResponse<List<ReceiptResponse>> getAllForLineItem(@PathVariable UUID lineItemId) {
        return ApiResponse.success(receiptService.getAllForLineItem(lineItemId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    @Operation(summary = "Get receipt metadata", description = "Returns metadata only — never the S3 object key.")
    public ApiResponse<ReceiptResponse> getById(@PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.getById(receiptId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}/view")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    @Operation(
            summary = "Get a time-limited URL to preview the receipt inline in a browser",
            description = "The URL is a pre-signed S3 GET link (default TTL: 15 minutes) — the bucket itself is "
                    + "never public. Generate on demand; do not cache long-term."
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Pre-signed view URL",
            content = @Content(schema = @Schema(implementation = ReceiptUrlResponse.class), examples = @ExampleObject(
                    value = "{\"success\":true,\"message\":\"Success\",\"data\":{\"url\":\"https://expense-management-files.s3."
                            + "ap-south-1.amazonaws.com/...(signed)...\",\"expiresAt\":\"2026-07-27T10:30:00\"}}"))))
    public ApiResponse<ReceiptUrlResponse> getViewUrl(@PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.getViewUrl(receiptId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}/download")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    @Operation(
            summary = "Get a time-limited URL to download the receipt",
            description = "Same pre-signed mechanism as /view, but forces a \"Save As\" download using the "
                    + "receipt's original file name instead of inline display."
    )
    public ApiResponse<ReceiptUrlResponse> getDownloadUrl(@PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.getDownloadUrl(receiptId));
    }

    @DeleteMapping("/xms/employee/receipts/{receiptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    @Operation(
            summary = "Delete a receipt",
            description = "Removes the file from S3 and the metadata row. Only the owning employee (or an Admin) "
                    + "may delete, and only while the parent report is Draft, Policy Rejected, or Query Raised."
    )
    public void delete(@PathVariable UUID receiptId) {
        receiptService.delete(receiptId);
    }
}
