package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.service.InvoiceSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoice-syncs")
@RequiredArgsConstructor
public class InvoiceSyncController {

    private final InvoiceSyncService invoiceSyncService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceSyncResponse> create(@Valid @RequestBody InvoiceSyncRequest request) {
        return ApiResponse.success("Invoice sync created", invoiceSyncService.create(request));
    }

    @PutMapping("/{syncId}")
    public ApiResponse<InvoiceSyncResponse> update(@PathVariable UUID syncId, @Valid @RequestBody InvoiceSyncRequest request) {
        return ApiResponse.success("Invoice sync updated", invoiceSyncService.update(syncId, request));
    }

    @GetMapping("/{syncId}")
    public ApiResponse<InvoiceSyncResponse> getById(@PathVariable UUID syncId) {
        return ApiResponse.success(invoiceSyncService.getById(syncId));
    }

    @GetMapping
    public ApiResponse<Page<InvoiceSyncResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(invoiceSyncService.getAll(pageable));
    }

    @DeleteMapping("/{syncId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID syncId) {
        invoiceSyncService.delete(syncId);
    }
}
