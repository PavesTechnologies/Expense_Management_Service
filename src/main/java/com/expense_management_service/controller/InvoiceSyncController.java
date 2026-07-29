package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.service.InvoiceSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/finance/invoice-handoffs")
@RequiredArgsConstructor
public class InvoiceSyncController {

    private final InvoiceSyncService invoiceSyncService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<InvoiceSyncResponse> create(@Valid @RequestBody InvoiceSyncRequest request) {
        return ApiResponse.success("Invoice sync created", invoiceSyncService.create(request));
    }

    @PutMapping("/{syncId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<InvoiceSyncResponse> update(@PathVariable UUID syncId, @Valid @RequestBody InvoiceSyncRequest request) {
        return ApiResponse.success("Invoice sync updated", invoiceSyncService.update(syncId, request));
    }

    @GetMapping("/{syncId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<InvoiceSyncResponse> getById(@PathVariable UUID syncId) {
        return ApiResponse.success(invoiceSyncService.getById(syncId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<List<InvoiceSyncResponse>> getAll() {
        return ApiResponse.success(invoiceSyncService.getAll());
    }

    @DeleteMapping("/{syncId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID syncId) {
        invoiceSyncService.delete(syncId);
    }
}
