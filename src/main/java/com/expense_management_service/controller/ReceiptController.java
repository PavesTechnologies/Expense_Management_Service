package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ReceiptRequest;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.service.ReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receiptService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReceiptResponse> create(@Valid @RequestBody ReceiptRequest request) {
        return ApiResponse.success("Receipt created", receiptService.create(request));
    }

    @PutMapping("/{receiptId}")
    public ApiResponse<ReceiptResponse> update(@PathVariable UUID receiptId, @Valid @RequestBody ReceiptRequest request) {
        return ApiResponse.success("Receipt updated", receiptService.update(receiptId, request));
    }

    @GetMapping("/{receiptId}")
    public ApiResponse<ReceiptResponse> getById(@PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.getById(receiptId));
    }

    @GetMapping
    public ApiResponse<Page<ReceiptResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(receiptService.getAll(pageable));
    }

    @DeleteMapping("/{receiptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID receiptId) {
        receiptService.delete(receiptId);
    }
}
