package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.service.ReceiptOcrService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/employee/receipt-ocr")
@RequiredArgsConstructor
public class ReceiptOcrController {

    private final ReceiptOcrService receiptOcrService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    public ApiResponse<ReceiptOcrResponse> create(@Valid @RequestBody ReceiptOcrRequest request) {
        return ApiResponse.success("Receipt OCR result created", receiptOcrService.create(request));
    }

    @PutMapping("/{ocrId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    public ApiResponse<ReceiptOcrResponse> update(@PathVariable UUID ocrId, @Valid @RequestBody ReceiptOcrRequest request) {
        return ApiResponse.success("Receipt OCR result updated", receiptOcrService.update(ocrId, request));
    }

    @GetMapping("/{ocrId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    public ApiResponse<ReceiptOcrResponse> getById(@PathVariable UUID ocrId) {
        return ApiResponse.success(receiptOcrService.getById(ocrId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    public ApiResponse<List<ReceiptOcrResponse>> getAll() {
        return ApiResponse.success(receiptOcrService.getAll());
    }

    @DeleteMapping("/{ocrId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID ocrId) {
        receiptOcrService.delete(ocrId);
    }
}
