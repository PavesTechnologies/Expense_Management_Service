package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ReceiptRequest;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.service.ReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/employee/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receiptService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    public ApiResponse<ReceiptResponse> create(@Valid @RequestBody ReceiptRequest request) {
        return ApiResponse.success("Receipt created", receiptService.create(request));
    }

    @PutMapping("/{receiptId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    public ApiResponse<ReceiptResponse> update(@PathVariable UUID receiptId, @Valid @RequestBody ReceiptRequest request) {
        return ApiResponse.success("Receipt updated", receiptService.update(receiptId, request));
    }

    @GetMapping("/{receiptId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    public ApiResponse<ReceiptResponse> getById(@PathVariable UUID receiptId) {
        return ApiResponse.success(receiptService.getById(receiptId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    public ApiResponse<List<ReceiptResponse>> getAll() {
        return ApiResponse.success(receiptService.getAll());
    }

    @DeleteMapping("/{receiptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID receiptId) {
        receiptService.delete(receiptId);
    }
}
