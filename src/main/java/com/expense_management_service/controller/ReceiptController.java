package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUploadResponse;
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
 * Receipt upload/retrieval/delete endpoints (Amazon S3-backed, EP03-S4).
 * <p>
 * A receipt is created directly under its {@code ExpenseReport}
 * ({@code /expense-reports/{reportId}/receipts}) — no line item is required, since OCR/employee
 * review is what determines the line item's fields. The legacy line-item-scoped listing endpoint
 * is retained for receipts that have since been linked to one. Read/delete are always by the
 * receipt's own id ({@code /receipts/{receiptId}}). Ownership and status-gating are enforced
 * inside {@link ReceiptService}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Receipts", description = "Upload, view, download and delete expense receipt files (stored in Amazon S3)")
public class ReceiptController {

    private final ReceiptService receiptService;

    @PostMapping(value = "/xms/employee/expense-reports/{reportId}/receipts", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    @Operation(
            summary = "Upload a receipt for an expense report",
            description = "Accepts a single multipart file. Allowed types: PDF, PNG, JPG, JPEG (validated by both "
                    + "declared content-type and actual file signature). Maximum size: 10MB. The report must be "
                    + "Draft, Policy Rejected, or Query Raised, and must belong to the caller (Admins may act on "
                    + "any report). No line item is required. Returns immediately — OCR is queued via an event and "
                    + "runs asynchronously in the background; poll GET /receipts/{receiptId}/ocr/status for progress. "
                    + "A line item is only created/linked once the employee confirms via POST /receipts/{receiptId}/confirm."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Receipt uploaded, OCR processing started",
                    content = @Content(schema = @Schema(implementation = ReceiptUploadResponse.class), examples = @ExampleObject(
                            value = "{\"success\":true,\"message\":\"Receipt uploaded successfully. OCR processing started.\","
                                    + "\"data\":{\"receiptId\":\"6f1a1e2e-1111-4a2b-9c3d-abc123456789\",\"processingStatus\":\"UPLOADED\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Empty file, oversized file, disallowed type, or content that doesn't match its declared type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller does not own the parent report"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Report not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "File exceeds the maximum allowed size"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Report is not in an editable status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Storage (S3) temporarily unavailable")
    })
    public ApiResponse<ReceiptUploadResponse> upload(@PathVariable UUID reportId,
                                                      @Parameter(description = "The receipt file (PDF/PNG/JPG/JPEG, max 10MB)")
                                                      @RequestParam("file") MultipartFile file) {
        ReceiptResponse saved = receiptService.upload(reportId, file);
        ReceiptUploadResponse response = new ReceiptUploadResponse(saved.receiptId(), saved.ocrStatus());
        return ApiResponse.success("Receipt uploaded successfully. OCR processing started.", response);
    }

    @GetMapping("/xms/employee/expense-reports/{reportId}/receipts")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    @Operation(
            summary = "List every receipt on a report",
            description = "Includes receipts not yet linked to any line item. Returns metadata only — no "
                    + "pre-signed URLs are generated here. Call the /view or /download endpoint for a specific "
                    + "receipt when a URL is actually needed."
    )
    public ApiResponse<List<ReceiptResponse>> getAllForReport(@PathVariable UUID reportId) {
        return ApiResponse.success(receiptService.getAllForReport(reportId));
    }

    @GetMapping("/xms/employee/expense-line-items/{lineItemId}/receipts")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    @Operation(
            summary = "List receipts linked to a line item",
            description = "Legacy, line-item-scoped view — only returns receipts that have already been "
                    + "confirmed/linked to this specific line item. Prefer the report-scoped listing endpoint for "
                    + "receipts still awaiting review."
    )
    public ApiResponse<List<ReceiptResponse>> getAllForLineItem(@PathVariable UUID lineItemId) {
        return ApiResponse.success(receiptService.getAllForLineItem(lineItemId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    @Operation(summary = "Get receipt metadata", description = "Returns metadata only — never the S3 object key.")
    public ApiResponse<ReceiptResponse> getById(@PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.getById(receiptId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}/view")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
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
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
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
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    @Operation(
            summary = "Delete a receipt",
            description = "Removes the file from S3 and the metadata row. Only the owning employee (or an Admin) "
                    + "may delete, and only while the parent report is Draft, Policy Rejected, or Query Raised."
    )
    public void delete(@PathVariable UUID receiptId) {
        receiptService.delete(receiptId);
    }
}
