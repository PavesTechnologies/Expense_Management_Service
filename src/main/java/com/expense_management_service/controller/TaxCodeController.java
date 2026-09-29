package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.service.TaxCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/tax-codes")
@RequiredArgsConstructor
public class TaxCodeController {

    private final TaxCodeService taxCodeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TaxCodeResponse> create(@Valid @RequestBody TaxCodeRequest request) {
        return ApiResponse.success("Tax code created", taxCodeService.create(request));
    }

    @PutMapping("/{taxCodeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TaxCodeResponse> update(@PathVariable UUID taxCodeId, @Valid @RequestBody TaxCodeRequest request) {
        return ApiResponse.success("Tax code updated", taxCodeService.update(taxCodeId, request));
    }

    @GetMapping("/{taxCodeId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<TaxCodeResponse> getById(@PathVariable UUID taxCodeId) {
        return ApiResponse.success(taxCodeService.getById(taxCodeId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<TaxCodeResponse>> getAll() {
        return ApiResponse.success(taxCodeService.getAll());
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<TaxCodeResponse>> getActive() {
        return ApiResponse.success(taxCodeService.getActive());
    }

    @DeleteMapping("/{taxCodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID taxCodeId) {
        taxCodeService.delete(taxCodeId);
    }
}
