package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.service.ReceiptOcrService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipt-ocr")
@RequiredArgsConstructor
public class ReceiptOcrController {

    private final ReceiptOcrService receiptOcrService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReceiptOcrResponse> create(@Valid @RequestBody ReceiptOcrRequest request) {
        return ApiResponse.success("Receipt OCR result created", receiptOcrService.create(request));
    }

    @PutMapping("/{ocrId}")
    public ApiResponse<ReceiptOcrResponse> update(@PathVariable UUID ocrId, @Valid @RequestBody ReceiptOcrRequest request) {
        return ApiResponse.success("Receipt OCR result updated", receiptOcrService.update(ocrId, request));
    }

    @GetMapping("/{ocrId}")
    public ApiResponse<ReceiptOcrResponse> getById(@PathVariable UUID ocrId) {
        return ApiResponse.success(receiptOcrService.getById(ocrId));
    }

    @GetMapping
    public ApiResponse<Page<ReceiptOcrResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(receiptOcrService.getAll(pageable));
    }

    @DeleteMapping("/{ocrId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ocrId) {
        receiptOcrService.delete(ocrId);
    }
}
