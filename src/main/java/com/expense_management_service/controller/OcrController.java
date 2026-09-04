package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.OcrOverrideRequest;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.OcrStatusResponse;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.service.OCRService;
import com.expense_management_service.service.ReceiptConfirmationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * OCR pipeline endpoints for a single receipt (EP03-S3/S4). Distinct from
 * {@link ReceiptOcrController}, which remains the generic admin CRUD resource for manually
 * correcting a {@code ReceiptOcr} row; this controller drives the actual Textract-backed
 * pipeline keyed by {@code receiptId} rather than {@code ocrId}.
 * <p>
 * Never creates an {@code ExpenseLineItem} on its own — {@code POST /confirm} is the one place
 * that happens, and it's handled by {@link ReceiptConfirmationService}, not {@link OCRService}.
 * Ownership (only the receipt's own report) is enforced inside both services.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Receipt OCR", description = "Trigger, retry, read, and confirm AWS Textract-based OCR extraction for an uploaded receipt")
public class OcrController {

    private final OCRService ocrService;
    private final ReceiptConfirmationService receiptConfirmationService;

    /**
     * NOT part of the normal employee workflow — OCR already starts automatically via
     * {@code ReceiptUploadedEvent} → {@code OcrEventListener} right after upload (see
     * {@code ReceiptServiceImpl.upload}). This endpoint exists only for admin-driven
     * reprocessing (e.g. re-running OCR after a parser fix) and testing; an employee's own
     * retry-after-failure path is {@code POST /ocr/retry}, not this one.
     */
    @PostMapping("/xms/employee/receipts/{receiptId}/ocr")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "[Admin/testing only] Force-run OCR extraction for a receipt",
            description = "OCR already starts automatically right after upload — employees never need to call "
                    + "this. Reserved for admin-driven reprocessing (e.g. re-running OCR after a parser fix) and "
                    + "testing. Unlike /retry, this has no status gate: it re-runs regardless of the receipt's "
                    + "current state. Never fails the request just because Textract failed: a FAILED result is "
                    + "returned normally."
    )
    public ApiResponse<ReceiptOcrResponse> processReceipt(@PathVariable UUID receiptId) {
        return ApiResponse.success("OCR processed", ocrService.processReceipt(receiptId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}/ocr")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    @Operation(
            summary = "Get the latest OCR result for a receipt",
            description = "Returns the most recent extraction attempt, for side-by-side review against the "
                    + "original receipt file and to pre-fill the Manual Entry screen. If OCR is still queued or "
                    + "running (no attempt exists yet), returns success=true with a lightweight status payload "
                    + "instead of an error — poll again shortly. Only a genuinely missing receipt is a 404."
    )
    public ApiResponse<?> getLatestResult(@PathVariable UUID receiptId) {
        try {
            return ApiResponse.success(ocrService.getLatestResult(receiptId));
        } catch (ResourceNotFoundException noResultYet) {
            OcrStatusResponse status = ocrService.getStatus(receiptId);
            // UPLOADED and PROCESSING are NOT the same thing to report to the caller: UPLOADED
            // means OCR hasn't even started yet (still queued for the async listener to pick
            // up), PROCESSING means it's genuinely running. Conflating them into one
            // "processing" message was itself a bug — it made a receipt that's stuck at
            // UPLOADED look identical to one that's legitimately in flight.
            if (status.ocrStatus() == OcrStatus.PROCESSING) {
                return ApiResponse.success("OCR is currently processing.", status);
            }
            if (status.ocrStatus() == OcrStatus.UPLOADED) {
                return ApiResponse.success("Receipt uploaded successfully. OCR has not started yet.", status);
            }
            // No ReceiptOcr row and the receipt isn't even in an in-flight state — a genuine
            // not-found (or a data inconsistency worth surfacing as an error either way).
            throw noResultYet;
        }
    }

    @PostMapping("/xms/employee/receipts/{receiptId}/ocr/retry")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(
            summary = "Retry OCR after a failed attempt",
            description = "The employee-facing retry path — used when automatic OCR (triggered on upload) came "
                    + "back FAILED. Re-runs extraction using the receipt's existing S3 object — never re-uploads. "
                    + "Rejected with 422 if the receipt's latest attempt did not fail."
    )
    public ApiResponse<ReceiptOcrResponse> retryOcr(@PathVariable UUID receiptId) {
        return ApiResponse.success("OCR retried", ocrService.retryOcr(receiptId));
    }

    @GetMapping("/xms/employee/receipts/{receiptId}/ocr/status")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    @Operation(
            summary = "Get lightweight OCR status for a receipt",
            description = "Meant for polling from the upload workflow's \"Loading\" step without pulling the "
                    + "full extracted result."
    )
    public ApiResponse<OcrStatusResponse> getStatus(@PathVariable UUID receiptId) {
        return ApiResponse.success(ocrService.getStatus(receiptId));
    }

    @PostMapping("/xms/employee/receipts/{receiptId}/ocr/override")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Record that an employee overrode an OCR-extracted value",
            description = "Audit-only extension point: call this when the employee edits a pre-filled OCR value "
                    + "before saving the expense line item. Deliberately decoupled from the line item save itself "
                    + "— it only records an OCR_OVERRIDE audit entry, nothing else."
    )
    public void recordOverride(@PathVariable UUID receiptId, @Valid @RequestBody OcrOverrideRequest request) {
        ocrService.recordOverride(receiptId, request.fieldName(), request.originalValue(), request.overriddenValue(), request.reason());
    }

    @PostMapping("/xms/employee/receipts/{receiptId}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    @Operation(
            summary = "Confirm a reviewed receipt into a real expense line item",
            description = "Creates a new expense line item from the receipt's OCR result merged with whatever "
                    + "the employee supplied (categoryId is always required — OCR never determines it), or, if "
                    + "lineItemId is provided, links the receipt to that existing line item and reports whether "
                    + "its amount differs from what OCR extracted."
    )
    public ApiResponse<ReceiptConfirmResponse> confirm(@PathVariable UUID receiptId, @RequestBody ReceiptConfirmRequest request) {
        return ApiResponse.success("Receipt confirmed", receiptConfirmationService.confirm(receiptId, request));
    }
}
