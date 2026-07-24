package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CurrencyRequest;
import com.expense_management_service.dto.response.CurrencyResponse;
import com.expense_management_service.service.CurrencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/currencies")
@RequiredArgsConstructor
public class CurrencyController {

    private final CurrencyService currencyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CurrencyResponse> create(@Valid @RequestBody CurrencyRequest request) {
        return ApiResponse.success("Currency created", currencyService.create(request));
    }

    @PutMapping("/{currencyId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CurrencyResponse> update(@PathVariable UUID currencyId, @Valid @RequestBody CurrencyRequest request) {
        return ApiResponse.success("Currency updated", currencyService.update(currencyId, request));
    }

    @GetMapping("/{currencyId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CurrencyResponse> getById(@PathVariable UUID currencyId) {
        return ApiResponse.success(currencyService.getById(currencyId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<CurrencyResponse>> getAll() {
        return ApiResponse.success(currencyService.getAll());
    }

    @DeleteMapping("/{currencyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID currencyId) {
        currencyService.delete(currencyId);
    }
}
